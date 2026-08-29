#!/usr/bin/env python3
"""EtherLink 市场服务：自带 SQLite，插件用 HTTP 接入。无需另行安装数据库。"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import math
import os
import secrets
import select
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

VERSION = "1.1.2"
DEFAULT_PORT = 8765
DEFAULT_NAME = "以太货栈"
FX_DEFAULT = {
    "window_hours": 24,
    "k": 0.25,
    "scale": 64,
    "min": 0.25,
    "max": 4.0,
}


def now_ms() -> int:
    return int(time.time() * 1000)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def claim_hash(code: str) -> str:
    digits = "".join(c for c in str(code or "") if c.isdigit())
    return hashlib.sha256(("eslink-claim-v1|" + digits).encode("utf-8")).hexdigest()


class Store:
    def __init__(self, path: Path, name: str, token: str, fx: dict | None = None):
        self.path = path
        self.lock = threading.Lock()
        self.fx = dict(FX_DEFAULT)
        if fx:
            self.fx.update(fx)
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA busy_timeout=5000")
        self._init(name, token)

    def _init(self, name: str, token: str) -> None:
        with self.lock:
            self.db.executescript(
                """
                CREATE TABLE IF NOT EXISTS meta (
                    k TEXT PRIMARY KEY,
                    v TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS servers (
                    code TEXT PRIMARY KEY,
                    name TEXT NOT NULL DEFAULT '',
                    blurb TEXT NOT NULL DEFAULT '',
                    color TEXT NOT NULL DEFAULT 'LIGHT_BLUE',
                    icon TEXT NOT NULL DEFAULT 'TERRACOTTA',
                    heartbeat INTEGER NOT NULL DEFAULT 0,
                    link_rate REAL NOT NULL DEFAULT 1,
                    rate_mode TEXT NOT NULL DEFAULT 'auto',
                    rate_base REAL NOT NULL DEFAULT 1
                );
                CREATE TABLE IF NOT EXISTS trades (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    ts INTEGER NOT NULL,
                    listing_id INTEGER,
                    seller_uuid TEXT,
                    seller_name TEXT,
                    seller_server TEXT NOT NULL,
                    buyer_uuid TEXT,
                    buyer_name TEXT,
                    buyer_server TEXT NOT NULL,
                    item_key TEXT,
                    item_name TEXT,
                    amount INTEGER,
                    price REAL NOT NULL,
                    kind TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_trades_ts ON trades(ts);
                CREATE TABLE IF NOT EXISTS listings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    seller_uuid TEXT NOT NULL,
                    seller_name TEXT NOT NULL,
                    server_code TEXT NOT NULL,
                    item_key TEXT NOT NULL,
                    item_name TEXT NOT NULL,
                    amount INTEGER NOT NULL,
                    price REAL NOT NULL,
                    created INTEGER NOT NULL,
                    blob_b64 TEXT,
                    nested_keys TEXT,
                    claim_hash TEXT NOT NULL DEFAULT '',
                    claim_code TEXT
                );
                CREATE UNIQUE INDEX IF NOT EXISTS idx_listings_claim ON listings(claim_code)
                    WHERE claim_code IS NOT NULL AND claim_code != '';
                CREATE INDEX IF NOT EXISTS idx_listings_server ON listings(server_code);
                CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid);
                """
            )
            self._meta_set("name", name)
            self._meta_set("token_hash", sha256(token))
            self._meta_set("version", VERSION)
            try:
                self.db.execute("ALTER TABLE servers ADD COLUMN link_rate REAL NOT NULL DEFAULT 1")
            except sqlite3.OperationalError:
                pass
            try:
                self.db.execute("ALTER TABLE servers ADD COLUMN rate_mode TEXT NOT NULL DEFAULT 'auto'")
            except sqlite3.OperationalError:
                pass
            try:
                self.db.execute("ALTER TABLE servers ADD COLUMN rate_base REAL NOT NULL DEFAULT 1")
            except sqlite3.OperationalError:
                pass
            if self._meta_get("fx_migrated") != "1":
                self.db.execute("UPDATE servers SET rate_base=link_rate")
                self._meta_set("fx_migrated", "1")
            try:
                self.db.execute("ALTER TABLE listings ADD COLUMN claim_code TEXT")
            except sqlite3.OperationalError:
                pass
            try:
                self.db.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_listings_claim ON listings(claim_code) "
                    "WHERE claim_code IS NOT NULL AND claim_code != ''"
                )
            except sqlite3.OperationalError:
                pass
            self.db.commit()

    def _meta_set(self, k: str, v: str) -> None:
        self.db.execute("INSERT OR REPLACE INTO meta(k, v) VALUES (?, ?)", (k, v))

    def _meta_get(self, k: str, default: str = "") -> str:
        row = self.db.execute("SELECT v FROM meta WHERE k=?", (k,)).fetchone()
        return row["v"] if row else default

    def name(self) -> str:
        with self.lock:
            return self._meta_get("name", DEFAULT_NAME)

    def token_ok(self, token: str) -> bool:
        if not token:
            return False
        with self.lock:
            expect = self._meta_get("token_hash")
        digest = sha256(token)
        if not expect or len(expect) != len(digest):
            return False
        return hmac.compare_digest(expect, digest)

    def info(self) -> dict:
        since = now_ms() - 24 * 3600 * 1000
        with self.lock:
            n = self.db.execute("SELECT COUNT(*) AS c FROM listings").fetchone()["c"]
            s = self.db.execute("SELECT COUNT(*) AS c FROM servers").fetchone()["c"]
            t = self.db.execute(
                "SELECT COUNT(*) AS c, COALESCE(SUM(price),0) AS v FROM trades WHERE kind='buy' AND ts>=?",
                (since,),
            ).fetchone()
            return {
                "ok": True,
                "name": self._meta_get("name", DEFAULT_NAME),
                "version": VERSION,
                "listings": n,
                "servers": s,
                "trades_24h": int(t["c"] or 0),
                "volume_24h": round(float(t["v"] or 0), 2),
            }

    def _clamp_rate(self, rate: float) -> float:
        rmin = max(0.01, float(self.fx.get("min") or 0.25))
        rmax = max(rmin, float(self.fx.get("max") or 4))
        if rate <= 0:
            rate = 1.0
        return round(min(max(rate, rmin), rmax), 2)

    def heartbeat(self, body: dict) -> dict:
        code = str(body.get("code") or "").strip().upper()
        if not code:
            raise ValueError("缺少服务器代号")
        name = str(body.get("name") or code)
        blurb = str(body.get("blurb") or "")
        color = str(body.get("color") or "LIGHT_BLUE")
        icon = str(body.get("icon") or "TERRACOTTA")
        try:
            rate = float(body.get("link_rate") or 1)
        except (TypeError, ValueError):
            rate = 1.0
        rate = self._clamp_rate(rate)
        want = str(body.get("rate_mode") or "").strip().lower()
        pin = bool(body.get("pin_rate"))
        with self.lock:
            cur = self.db.execute(
                "SELECT link_rate, rate_mode, rate_base FROM servers WHERE code=?", (code,)
            ).fetchone()
            if cur is None:
                mode = "manual" if (pin or want == "manual") else "auto"
                if want == "auto":
                    mode = "auto"
                self.db.execute(
                    """
                    INSERT INTO servers(code, name, blurb, color, icon, heartbeat, link_rate, rate_mode, rate_base)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (code, name, blurb, color, icon, now_ms(), rate, mode, rate),
                )
            else:
                mode = str(cur["rate_mode"] or "auto")
                stored = float(cur["link_rate"] or 1)
                base = float(cur["rate_base"] or stored or 1)
                if want == "auto":
                    mode = "auto"
                    base = stored if stored > 0 else rate
                elif want == "manual" or pin:
                    mode = "manual"
                    stored = rate
                    base = rate
                elif mode == "manual":
                    stored = rate
                    base = rate
                self.db.execute(
                    """
                    UPDATE servers SET name=?, blurb=?, color=?, icon=?, heartbeat=?,
                        link_rate=?, rate_mode=?, rate_base=?
                    WHERE code=?
                    """,
                    (name, blurb, color, icon, now_ms(), stored, mode, base, code),
                )
            if mode == "auto":
                self._fx_apply_locked()
            row = self.db.execute(
                "SELECT link_rate, rate_mode FROM servers WHERE code=?", (code,)
            ).fetchone()
            self.db.commit()
            out_rate = float(row["link_rate"] or 1) if row else rate
            out_mode = str(row["rate_mode"] or "auto") if row else "auto"
            return {
                "ok": True,
                "name": self._meta_get("name", DEFAULT_NAME),
                "link_rate": out_rate,
                "rate_mode": out_mode,
            }

    def servers(self, offline_after_ms: int = 20000) -> list[dict]:
        clock = now_ms()
        since = clock - int(float(self.fx.get("window_hours") or 24) * 3600 * 1000)
        with self.lock:
            rows = self.db.execute(
                """
                SELECT code, name, blurb, color, icon, heartbeat, link_rate, rate_mode, rate_base
                FROM servers ORDER BY name
                """
            ).fetchall()
            flow = self._flow_locked(since)
        out = []
        for r in rows:
            age = clock - int(r["heartbeat"] or 0)
            try:
                rate = float(r["link_rate"] or 1)
            except (TypeError, ValueError, KeyError):
                rate = 1.0
            if rate <= 0:
                rate = 1.0
            try:
                base = float(r["rate_base"] or 1)
            except (TypeError, ValueError, KeyError):
                base = 1.0
            mode = "manual"
            try:
                mode = str(r["rate_mode"] or "auto")
            except (TypeError, ValueError, KeyError):
                pass
            exp, imp = flow.get(str(r["code"]), (0.0, 0.0))
            out.append(
                {
                    "code": r["code"],
                    "name": r["name"],
                    "blurb": r["blurb"],
                    "color": r["color"],
                    "icon": r["icon"],
                    "link_rate": rate,
                    "rate_mode": mode,
                    "rate_base": base,
                    "mode": mode,
                    "export": round(exp, 2),
                    "import": round(imp, 2),
                    "net": round(exp - imp, 2),
                    "heartbeat": r["heartbeat"],
                    "clock": clock,
                    "online": 0 <= age < offline_after_ms,
                }
            )
        return out

    def _flow_locked(self, since: int) -> dict[str, tuple[float, float]]:
        out: dict[str, tuple[float, float]] = {}
        rows = self.db.execute(
            """
            SELECT seller_server, buyer_server, SUM(price) AS v
            FROM trades WHERE kind='buy' AND ts>=? AND seller_server!=buyer_server
            GROUP BY seller_server, buyer_server
            """,
            (since,),
        ).fetchall()
        for r in rows:
            v = float(r["v"] or 0)
            a = str(r["seller_server"] or "")
            b = str(r["buyer_server"] or "")
            exp, imp = out.get(a, (0.0, 0.0))
            out[a] = (exp + v, imp)
            exp, imp = out.get(b, (0.0, 0.0))
            out[b] = (exp, imp + v)
        return out

    def _flow_pairs_locked(self, since: int) -> list[dict]:
        rows = self.db.execute(
            """
            SELECT seller_server, buyer_server, SUM(price) AS v
            FROM trades WHERE kind='buy' AND ts>=? AND seller_server!=buyer_server
            GROUP BY seller_server, buyer_server
            """,
            (since,),
        ).fetchall()
        out = []
        for r in rows:
            v = round(float(r["v"] or 0), 2)
            if v <= 0:
                continue
            a = str(r["seller_server"] or "").strip()
            b = str(r["buyer_server"] or "").strip()
            if not a or not b:
                continue
            out.append({"from": a, "to": b, "vol": v})
        out.sort(key=lambda x: -x["vol"])
        return out

    def listings_desk(self, limit: int = 36) -> list[dict]:
        rows = self.listings(None, None, None, 0, max(1, min(int(limit), 36)))
        return [
            {
                "id": r["id"],
                "item_name": r["item_name"],
                "item_key": r["item_key"],
                "amount": r["amount"],
                "price": float(r["price"] or 0),
                "seller_name": r["seller_name"],
                "server_code": r["server_code"],
                "created": r["created"],
            }
            for r in rows
        ]

    def fx_series(self, points: int = 24) -> list[dict]:
        n = max(2, min(int(points), 48))
        clock = now_ms()
        window_ms = int(float(self.fx.get("window_hours") or 24) * 3600 * 1000)
        step = max(1, window_ms // (n - 1))
        k = max(0.0, float(self.fx.get("k") or 0))
        scale = max(1.0, float(self.fx.get("scale") or 64))
        with self.lock:
            trades = self.db.execute(
                """
                SELECT ts, seller_server, buyer_server, price
                FROM trades
                WHERE kind='buy' AND ts>=? AND seller_server!=buyer_server
                """,
                (clock - window_ms - window_ms,),
            ).fetchall()
            servers = self.db.execute(
                "SELECT code, rate_mode, rate_base, link_rate FROM servers ORDER BY code"
            ).fetchall()
        events = [
            (
                int(r["ts"] or 0),
                str(r["seller_server"] or ""),
                str(r["buyer_server"] or ""),
                float(r["price"] or 0),
            )
            for r in trades
        ]
        out = []
        for r in servers:
            code = str(r["code"] or "")
            mode = str(r["rate_mode"] or "auto")
            try:
                base = float(r["rate_base"] or r["link_rate"] or 1)
            except (TypeError, ValueError):
                base = 1.0
            if base <= 0:
                base = 1.0
            try:
                actual = float(r["link_rate"] or 1)
            except (TypeError, ValueError):
                actual = 1.0
            pts = []
            for i in range(n):
                t = clock - (n - 1 - i) * step
                if mode != "auto":
                    rate = actual
                else:
                    exp = 0.0
                    imp = 0.0
                    lo = t - window_ms
                    for ts, seller, buyer, price in events:
                        if ts < lo or ts > t:
                            continue
                        if seller == code:
                            exp += price
                        if buyer == code:
                            imp += price
                    rate = self._clamp_rate(base * (1.0 + k * math.tanh((exp - imp) / scale)))
                pts.append({"t": t, "rate": rate})
            if pts:
                pts[-1] = {"t": clock, "rate": round(actual, 2)}
            out.append({"code": code, "mode": mode, "points": pts})
        return out

    def _fx_apply_locked(self) -> None:
        k = max(0.0, float(self.fx.get("k") or 0))
        scale = max(1.0, float(self.fx.get("scale") or 64))
        since = now_ms() - int(float(self.fx.get("window_hours") or 24) * 3600 * 1000)
        flow = self._flow_locked(since)
        rows = self.db.execute(
            "SELECT code, rate_mode, rate_base, link_rate FROM servers"
        ).fetchall()
        for r in rows:
            if str(r["rate_mode"] or "auto") != "auto":
                continue
            base = float(r["rate_base"] or r["link_rate"] or 1)
            if base <= 0:
                base = 1.0
            exp, imp = flow.get(str(r["code"]), (0.0, 0.0))
            pressure = math.tanh((exp - imp) / scale)
            new = self._clamp_rate(base * (1.0 + k * pressure))
            self.db.execute("UPDATE servers SET link_rate=? WHERE code=?", (new, r["code"]))

    def set_server_fx(self, code: str, mode: str | None = None, rate: float | None = None) -> dict:
        code = str(code or "").strip().upper()
        if not code:
            raise ValueError("缺少服务器代号")
        with self.lock:
            row = self.db.execute("SELECT link_rate, rate_mode, rate_base FROM servers WHERE code=?", (code,)).fetchone()
            if row is None:
                raise ValueError("没有这台服务器")
            stored = float(row["link_rate"] or 1)
            base = float(row["rate_base"] or stored or 1)
            cur_mode = str(row["rate_mode"] or "auto")
            if mode:
                m = mode.strip().lower()
                if m not in ("auto", "manual"):
                    raise ValueError("mode 只能是 auto 或 manual")
                cur_mode = m
                if m == "auto":
                    base = stored
                elif rate is None:
                    base = stored
            if rate is not None:
                stored = self._clamp_rate(float(rate))
                base = stored
                cur_mode = "manual"
            self.db.execute(
                "UPDATE servers SET link_rate=?, rate_mode=?, rate_base=? WHERE code=?",
                (stored, cur_mode, base, code),
            )
            if cur_mode == "auto":
                self._fx_apply_locked()
            self.db.commit()
            row = self.db.execute(
                "SELECT link_rate, rate_mode, rate_base FROM servers WHERE code=?", (code,)
            ).fetchone()
            return {
                "ok": True,
                "code": code,
                "link_rate": float(row["link_rate"]),
                "rate_mode": row["rate_mode"],
                "rate_base": float(row["rate_base"]),
            }

    def set_fx_params(self, patch: dict) -> dict:
        fx = dict(self.fx)
        for key, lo, hi in (
            ("window_hours", 1, 168),
            ("k", 0, 2),
            ("scale", 1, 1_000_000),
            ("min", 0.01, 100),
            ("max", 0.01, 1000),
        ):
            if key not in patch:
                continue
            try:
                v = float(patch[key])
            except (TypeError, ValueError) as e:
                raise ValueError(f"{key} 无效") from e
            if v < lo or v > hi:
                raise ValueError(f"{key} 超出范围")
            fx[key] = int(v) if key in ("window_hours", "scale") else round(v, 4)
        if fx["min"] > fx["max"]:
            raise ValueError("min 不能大于 max")
        self.fx = fx
        with self.lock:
            self._fx_apply_locked()
            self.db.commit()
        return {"ok": True, "fx": dict(self.fx)}

    def trades_recent(self, limit: int = 40) -> list[dict]:
        n = max(1, min(int(limit), 80))
        with self.lock:
            rows = self.db.execute(
                """
                SELECT ts, listing_id, seller_name, seller_server, buyer_name, buyer_server,
                       item_name, amount, price, kind
                FROM trades ORDER BY id DESC LIMIT ?
                """,
                (n,),
            ).fetchall()
        out = []
        for r in rows:
            out.append(
                {
                    "ts": int(r["ts"] or 0),
                    "listing_id": r["listing_id"],
                    "seller_name": r["seller_name"] or "",
                    "seller_server": r["seller_server"],
                    "buyer_name": r["buyer_name"] or "",
                    "buyer_server": r["buyer_server"],
                    "item_name": r["item_name"] or "",
                    "amount": int(r["amount"] or 0),
                    "price": float(r["price"] or 0),
                    "kind": r["kind"],
                }
            )
        return out

    def dashboard(self) -> dict:
        info = self.info()
        since = now_ms() - int(float(self.fx.get("window_hours") or 24) * 3600 * 1000)
        with self.lock:
            flow = self._flow_pairs_locked(since)
        return {
            "ok": True,
            "name": info["name"],
            "version": VERSION,
            "info": info,
            "fx": dict(self.fx),
            "servers": self.servers(),
            "trades": self.trades_recent(),
            "listings": self.listings_desk(36),
            "flow": flow[:16],
            "fx_series": self.fx_series(24),
        }

    def _listing_dict(self, r: sqlite3.Row) -> dict:
        return {
            "id": r["id"],
            "seller_uuid": r["seller_uuid"],
            "seller_name": r["seller_name"],
            "server_code": r["server_code"],
            "item_key": r["item_key"],
            "item_name": r["item_name"],
            "amount": r["amount"],
            "price": r["price"],
            "created": r["created"],
            "blob_b64": r["blob_b64"] or "",
            "nested_keys": r["nested_keys"] or "",
            "claim_code": str(r["claim_code"] if "claim_code" in r.keys() else "") or "",
            "has_claim": bool(str(r["claim_code"] if "claim_code" in r.keys() else "").strip()),
        }

    def listings(self, server: str | None, query: str | None, seller: str | None,
                 offset: int, limit: int) -> list[dict]:
        sql = "SELECT * FROM listings WHERE 1=1"
        args: list = []
        if server:
            sql += " AND server_code=?"
            args.append(server)
        if seller:
            sql += " AND seller_uuid=?"
            args.append(seller)
        if query:
            sql += " AND (item_name LIKE ? OR item_key LIKE ? OR seller_name LIKE ?)"
            q = f"%{query}%"
            args.extend([q, q, q])
        sql += " ORDER BY id DESC LIMIT ? OFFSET ?"
        args.extend([max(1, min(limit, 64)), max(0, offset)])
        with self.lock:
            rows = self.db.execute(sql, args).fetchall()
        return [self._listing_dict(r) for r in rows]

    def listing(self, listing_id: int) -> dict | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM listings WHERE id=?", (listing_id,)).fetchone()
        return self._listing_dict(r) if r else None

    def insert(self, body: dict) -> dict:
        required = ("seller_uuid", "seller_name", "server_code", "item_key", "item_name")
        for k in required:
            if not str(body.get(k) or "").strip():
                raise ValueError(f"缺少 {k}")
        amount = int(body.get("amount") or 1)
        if amount < 1:
            raise ValueError("数量无效")
        price = float(body.get("price") or 0)
        if price < 0:
            raise ValueError("价格无效")
        raw_code = str(body.get("claim_code") or "").strip()
        digits = "".join(c for c in raw_code if c.isdigit())
        code = digits if len(digits) == 6 else None
        with self.lock:
            cur = self.db.execute(
                """
                INSERT INTO listings(seller_uuid, seller_name, server_code, item_key, item_name,
                                     amount, price, created, blob_b64, nested_keys, claim_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    str(body["seller_uuid"]),
                    str(body["seller_name"]),
                    str(body["server_code"]).upper(),
                    str(body["item_key"]),
                    str(body["item_name"]),
                    amount,
                    price,
                    now_ms(),
                    str(body.get("blob_b64") or ""),
                    str(body.get("nested_keys") or ""),
                    code,
                ),
            )
            self.db.commit()
            lid = cur.lastrowid
        row = self.listing(lid)
        if row is None:
            raise RuntimeError("上架写入失败")
        return row

    def delete(self, listing_id: int) -> bool:
        with self.lock:
            cur = self.db.execute("DELETE FROM listings WHERE id=?", (listing_id,))
            self.db.commit()
            return cur.rowcount > 0

    def claim(self, listing_id: int, buyer: dict | None = None) -> dict | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM listings WHERE id=?", (listing_id,)).fetchone()
            if r is None:
                return None
            self.db.execute("DELETE FROM listings WHERE id=?", (listing_id,))
            kind = str((buyer or {}).get("kind") or "buy").strip().lower()
            if kind not in ("buy", "pickup"):
                kind = "buy"
            buyer_server = str((buyer or {}).get("buyer_server") or "").strip().upper()
            if buyer and kind == "buy" and buyer_server:
                seller_server = str(r["server_code"] or "").upper()
                self.db.execute(
                    """
                    INSERT INTO trades(ts, listing_id, seller_uuid, seller_name, seller_server,
                                       buyer_uuid, buyer_name, buyer_server, item_key, item_name,
                                       amount, price, kind)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        now_ms(),
                        listing_id,
                        str(r["seller_uuid"] or ""),
                        str(r["seller_name"] or ""),
                        seller_server,
                        str((buyer or {}).get("buyer_uuid") or ""),
                        str((buyer or {}).get("buyer_name") or ""),
                        buyer_server,
                        str(r["item_key"] or ""),
                        str(r["item_name"] or ""),
                        int(r["amount"] or 0),
                        float(r["price"] or 0),
                        kind,
                    ),
                )
                self.db.execute("DELETE FROM trades WHERE ts < ?", (now_ms() - 30 * 86400 * 1000,))
                self._fx_apply_locked()
            self.db.commit()
            return self._listing_dict(r)

    def claim_taken(self, code: str) -> bool:
        digits = "".join(c for c in str(code or "") if c.isdigit())
        if len(digits) != 6:
            return False
        with self.lock:
            r = self.db.execute("SELECT 1 FROM listings WHERE claim_code=?", (digits,)).fetchone()
        return r is not None

    def listing_by_claim(self, code: str) -> dict | None:
        digits = "".join(c for c in str(code or "") if c.isdigit())
        if len(digits) != 6:
            return None
        with self.lock:
            r = self.db.execute("SELECT * FROM listings WHERE claim_code=?", (digits,)).fetchone()
        return self._listing_dict(r) if r else None

    def verify_claim(self, listing_id: int, code: str) -> bool:
        with self.lock:
            r = self.db.execute("SELECT claim_hash FROM listings WHERE id=?", (listing_id,)).fetchone()
        if r is None:
            return False
        stored = str(r["claim_hash"] or "")
        if not stored.strip():
            return False
        return stored.lower() == claim_hash(code)

    def set_price(self, listing_id: int, price: float) -> bool:
        if price < 0:
            raise ValueError("价格无效")
        with self.lock:
            cur = self.db.execute("UPDATE listings SET price=? WHERE id=?", (price, listing_id))
            self.db.commit()
            return cur.rowcount > 0

    def delete_of(self, server: str, seller: str) -> int:
        with self.lock:
            cur = self.db.execute(
                "DELETE FROM listings WHERE server_code=? AND seller_uuid=?",
                (server, seller),
            )
            self.db.commit()
            return cur.rowcount


def load_or_create_config(data_dir: Path, args: argparse.Namespace) -> dict:
    cfg_path = data_dir / "market.json"
    cfg = {}
    if cfg_path.exists():
        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    name = args.name or cfg.get("name") or DEFAULT_NAME
    port = args.port if args.port else int(cfg.get("port") or DEFAULT_PORT)
    token = args.token or os.environ.get("ETHERLINK_TOKEN") or cfg.get("token")
    generated = False
    if not token:
        token = secrets.token_urlsafe(24)
        generated = True
    fx = dict(FX_DEFAULT)
    if isinstance(cfg.get("fx"), dict):
        fx.update(cfg["fx"])
    out = {"name": name, "port": port, "token": token, "fx": fx}
    data_dir.mkdir(parents=True, exist_ok=True)
    write_config(cfg_path, out)
    if generated:
        print(f"已生成接入令牌，写入 {cfg_path}", file=sys.stderr)
        print(f"token = {token}", file=sys.stderr)
    return out


def write_config(cfg_path: Path, cfg: dict) -> None:
    cfg_path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


HERE = Path(__file__).resolve().parent
DASHBOARD_HTML = HERE / "dashboard.html"


class Handler(BaseHTTPRequestHandler):
    store: Store
    token: str
    cfg_path: Path
    cfg: dict

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[market] " + (fmt % args) + "\n")

    def _token(self) -> str:
        h = self.headers.get("Authorization") or ""
        if h.lower().startswith("bearer "):
            return h[7:].strip()
        q = parse_qs(urlparse(self.path).query)
        if q.get("token"):
            return (q.get("token") or [""])[0]
        return (self.headers.get("X-Join-Token") or "").strip()

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        if n > 2_000_000:
            raise ValueError("请求过大")
        raw = self.rfile.read(n)
        if not raw:
            return {}
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("JSON 必须是对象")
        return data

    def _send(self, code: int, body: dict) -> None:
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _send_bytes(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        if "text/html" in content_type:
            self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _need_auth(self) -> bool:
        if not self.store.token_ok(self._token()):
            self._send(401, {"ok": False, "error": "令牌无效"})
            return False
        return True

    def _persist_fx(self) -> None:
        self.cfg["fx"] = dict(self.store.fx)
        write_config(self.cfg_path, self.cfg)

    def do_GET(self) -> None:
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        q = parse_qs(u.query)
        try:
            if path == "/favicon.ico":
                self.send_response(204)
                self.end_headers()
                return
            if path == "/" or path == "/index.html":
                accept = (self.headers.get("Accept") or "").lower()
                if "application/json" in accept:
                    self._send(200, self.store.info())
                    return
                if DASHBOARD_HTML.exists():
                    self._send_bytes(200, DASHBOARD_HTML.read_bytes(), "text/html; charset=utf-8")
                else:
                    self._send(200, self.store.info())
                return
            if path == "/v1/info":
                self._send(200, self.store.info())
                return
            if path == "/health":
                self._send(200, {"ok": True})
                return
            if not self._need_auth():
                return
            if path == "/v1/dashboard":
                self._send(200, self.store.dashboard())
                return
            if path == "/v1/servers":
                self._send(200, {"ok": True, "servers": self.store.servers()})
                return
            if path == "/v1/listings/claim-taken":
                code = (q.get("code") or [""])[0]
                self._send(200, {"ok": True, "taken": self.store.claim_taken(code)})
                return
            if path == "/v1/listings":
                server = (q.get("server") or [None])[0]
                query = (q.get("q") or [None])[0]
                seller = (q.get("seller") or [None])[0]
                offset = int((q.get("offset") or ["0"])[0])
                limit = int((q.get("limit") or ["36"])[0])
                rows = self.store.listings(server, query, seller, offset, limit)
                self._send(200, {"ok": True, "listings": rows})
                return
            if path.startswith("/v1/listings/"):
                lid = int(path.split("/")[-1])
                row = self.store.listing(lid)
                if row is None:
                    self._send(404, {"ok": False, "error": "货物不存在"})
                    return
                self._send(200, {"ok": True, "listing": row})
                return
            self._send(404, {"ok": False, "error": "未知路径"})
        except Exception as e:
            self._send(400, {"ok": False, "error": str(e)})

    def do_POST(self) -> None:
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        try:
            if not self._need_auth():
                return
            body = self._read_json()
            if path == "/v1/heartbeat":
                self._send(200, self.store.heartbeat(body))
                return
            if path == "/v1/fx":
                if "fx" in body and isinstance(body["fx"], dict):
                    out = self.store.set_fx_params(body["fx"])
                    self._persist_fx()
                    self._send(200, out)
                    return
                code = str(body.get("code") or "")
                mode = body.get("mode")
                rate = body.get("rate")
                if rate is not None:
                    rate = float(rate)
                out = self.store.set_server_fx(code, None if mode is None else str(mode), rate)
                self._send(200, out)
                return
            if path == "/v1/listings/by-claim":
                row = self.store.listing_by_claim(str(body.get("code") or ""))
                if row is None:
                    self._send(404, {"ok": False, "error": "货物不存在"})
                    return
                self._send(200, {"ok": True, "listing": row})
                return
            if path == "/v1/listings":
                row = self.store.insert(body)
                self._send(200, {"ok": True, "listing": row})
                return
            if path.endswith("/verify-claim") and path.startswith("/v1/listings/"):
                lid = int(path.split("/")[-2])
                ok = self.store.verify_claim(lid, str(body.get("code") or ""))
                self._send(200, {"ok": True, "match": ok})
                return
            if path.endswith("/claim") and path.startswith("/v1/listings/"):
                lid = int(path.split("/")[-2])
                row = self.store.claim(lid, body)
                if row is None:
                    self._send(409, {"ok": False, "error": "货物已被取走"})
                    return
                self._send(200, {"ok": True, "listing": row})
                return
            self._send(404, {"ok": False, "error": "未知路径"})
        except ValueError as e:
            self._send(400, {"ok": False, "error": str(e)})
        except Exception as e:
            self._send(500, {"ok": False, "error": str(e)})

    def do_PATCH(self) -> None:
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        try:
            if not self._need_auth():
                return
            if not path.startswith("/v1/listings/"):
                self._send(404, {"ok": False, "error": "未知路径"})
                return
            lid = int(path.split("/")[-1])
            body = self._read_json()
            price = float(body.get("price"))
            if not self.store.set_price(lid, price):
                self._send(404, {"ok": False, "error": "货物不存在"})
                return
            self._send(200, {"ok": True})
        except Exception as e:
            self._send(400, {"ok": False, "error": str(e)})

    def do_DELETE(self) -> None:
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        q = parse_qs(u.query)
        try:
            if not self._need_auth():
                return
            if path == "/v1/listings":
                server = (q.get("server") or [""])[0]
                seller = (q.get("seller") or [""])[0]
                if not server or not seller:
                    self._send(400, {"ok": False, "error": "需要 server 与 seller"})
                    return
                n = self.store.delete_of(server, seller)
                self._send(200, {"ok": True, "deleted": n})
                return
            if path.startswith("/v1/listings/"):
                lid = int(path.split("/")[-1])
                if not self.store.delete(lid):
                    self._send(404, {"ok": False, "error": "货物不存在"})
                    return
                self._send(200, {"ok": True})
                return
            self._send(404, {"ok": False, "error": "未知路径"})
        except Exception as e:
            self._send(400, {"ok": False, "error": str(e)})


def seed_demo(store: Store) -> None:
    """本机预览用：两台服 + 几笔成交 + 仍在架的货。已有成交则只刷新心跳。"""
    store.heartbeat({
        "code": "ES2", "name": "以太物语", "blurb": "Create 机械动力 · 生存建筑",
        "color": "LIGHT_BLUE", "icon": "TERRACOTTA", "link_rate": 1.0,
    })
    store.heartbeat({
        "code": "SNC", "name": "SNC", "blurb": "工业 / 物流",
        "color": "ORANGE", "icon": "CONCRETE", "link_rate": 1.0,
    })
    info = store.info()
    if int(info.get("trades_24h") or 0) > 0 or int(info.get("listings") or 0) > 0:
        _spread_demo_trades(store)
        return

    def put(server: str, item: str, key: str, amount: int, price: float, who: str, uid: str) -> dict:
        return store.insert({
            "seller_uuid": uid,
            "seller_name": who,
            "server_code": server,
            "item_key": key,
            "item_name": item,
            "amount": amount,
            "price": price,
        })

    alex = "00000000-0000-0000-0000-0000000000a1"
    steve = "00000000-0000-0000-0000-0000000000b2"
    put("ES2", "钻石", "minecraft:diamond", 8, 48, "Alex", alex)
    put("ES2", "铁锭", "minecraft:iron_ingot", 32, 16, "Alex", alex)
    put("SNC", "橡木原木", "minecraft:oak_log", 64, 8, "Steve", steve)
    row = put("ES2", "钻石块", "minecraft:diamond_block", 2, 120, "Alex", alex)
    store.claim(row["id"], {
        "buyer_uuid": steve, "buyer_name": "Steve", "buyer_server": "SNC", "kind": "buy",
    })
    row = put("SNC", "红石", "minecraft:redstone", 64, 24, "Steve", steve)
    store.claim(row["id"], {
        "buyer_uuid": alex, "buyer_name": "Alex", "buyer_server": "ES2", "kind": "buy",
    })
    row = put("ES2", "金锭", "minecraft:gold_ingot", 16, 40, "Alex", alex)
    store.claim(row["id"], {
        "buyer_uuid": steve, "buyer_name": "Steve", "buyer_server": "SNC", "kind": "buy",
    })
    row = put("ES2", "下界合金锭", "minecraft:netherite_ingot", 1, 80, "Alex", alex)
    store.claim(row["id"], {
        "buyer_uuid": alex, "buyer_name": "Alex", "buyer_server": "SNC", "kind": "pickup",
    })
    _spread_demo_trades(store)


def _spread_demo_trades(store: Store) -> None:
    """预览用：成交挤在同一秒时摊到近 18 小时；基准卡在夹取边则拉回 1。"""
    with store.lock:
        dirty = False
        rows = store.db.execute(
            "SELECT id, ts FROM trades WHERE kind='buy' ORDER BY id"
        ).fetchall()
        if len(rows) >= 2:
            times = [int(r["ts"] or 0) for r in rows]
            if max(times) - min(times) <= 30 * 60 * 1000:
                clock = now_ms()
                n = len(rows)
                for i, r in enumerate(rows):
                    t = clock - int((n - i) / n * 18 * 3600 * 1000)
                    store.db.execute("UPDATE trades SET ts=? WHERE id=?", (t, r["id"]))
                dirty = True
        rmin = max(0.01, float(store.fx.get("min") or 0.25))
        rmax = max(rmin, float(store.fx.get("max") or 4))
        for r in store.db.execute("SELECT code, rate_base FROM servers").fetchall():
            try:
                base = float(r["rate_base"] or 1)
            except (TypeError, ValueError):
                base = 1.0
            if base <= rmin + 1e-6 or base >= rmax - 1e-6:
                store.db.execute("UPDATE servers SET rate_base=1 WHERE code=?", (r["code"],))
                dirty = True
        if dirty:
            store._fx_apply_locked()
            store.db.commit()


def banner(cfg: dict, host: str, data_dir: Path) -> str:
    show = host if host not in ("0.0.0.0", "::") else "127.0.0.1"
    port = cfg["port"]
    lines = [
        f"EtherLink 市场「{cfg['name']}」  {VERSION}",
        f"监听  {host}:{port}",
        f"Web   http://{show}:{port}/",
        f"数据  {data_dir}",
        f"token {cfg['token']}",
        "",
    ]
    return "\n".join(lines)


def format_status(store: Store) -> str:
    d = store.dashboard()
    fx = d.get("fx") or {}
    info = d.get("info") or {}
    lines = [
        f"货单 {info.get('listings', 0)}  服 {info.get('servers', 0)}  "
        f"24h成交 {info.get('trades_24h', 0)}  额 {info.get('volume_24h', 0)}",
        f"FX 窗 {fx.get('window_hours')}h  K {fx.get('k')}  SCALE {fx.get('scale')}  "
        f"{fx.get('min')}–{fx.get('max')}",
        "",
    ]
    for s in d.get("servers") or []:
        on = "ON " if s.get("online") else "off"
        mode = str(s.get("mode") or "auto").upper()[:3]
        net = s.get("net") or 0
        sign = "+" if net >= 0 else ""
        lines.append(
            f"  {str(s.get('code') or ''):<8} {on} {mode:<3}  1:{s.get('link_rate')}  "
            f"净出口 {sign}{net}  {s.get('name') or ''}"
        )
    if not (d.get("servers") or []):
        lines.append("  （还没有服务器心跳）")
    trades = d.get("trades") or []
    if trades:
        lines.append("")
        lines.append("最近成交")
        for t in trades[:6]:
            lines.append(
                f"  {t.get('buyer_name') or '某人'}（{t.get('buyer_server')}）从 "
                f"{t.get('seller_server')} 的 {t.get('seller_name') or '卖家'} "
                f"买了 {t.get('item_name')} ×{t.get('amount')}  {t.get('price')} 互通"
            )
    lines.append("")
    lines.append("命令: status | auto CODE | rate CODE 1.2 | fx | fx k 0.25 | help | quit")
    return "\n".join(lines)


def handle_cmd(store: Store, cfg: dict, cfg_path: Path, line: str) -> str | None:
    raw = (line or "").strip()
    if not raw:
        return None
    parts = raw.split()
    cmd = parts[0].lower()
    if cmd in ("q", "quit", "exit"):
        return "QUIT"
    if cmd in ("h", "help", "?"):
        return "status 刷新   auto CODE 跟随成交   rate CODE 1.2 钉死\nfx 查看参数   fx k 0.25 改参数   quit 退出"
    if cmd in ("s", "status", "st"):
        return format_status(store)
    if cmd == "auto":
        if len(parts) < 2:
            return "用法: auto ES2"
        out = store.set_server_fx(parts[1], "auto", None)
        return f"{out['code']} 已改为自动  当前 1:{out['link_rate']}"
    if cmd == "rate":
        if len(parts) < 3:
            return "用法: rate ES2 1.2"
        out = store.set_server_fx(parts[1], "manual", float(parts[2]))
        return f"{out['code']} 已钉死  1:{out['link_rate']}"
    if cmd == "fx":
        if len(parts) == 1:
            fx = store.fx
            return " ".join(f"{k}={v}" for k, v in fx.items())
        if len(parts) < 3:
            return "用法: fx k 0.25"
        key = parts[1]
        out = store.set_fx_params({key: float(parts[2])})
        cfg["fx"] = dict(store.fx)
        write_config(cfg_path, cfg)
        return " ".join(f"{k}={v}" for k, v in out["fx"].items())
    return "未知命令。help 查看。"


def run_console(httpd: ThreadingHTTPServer, store: Store, cfg: dict, cfg_path: Path,
                host: str, data_dir: Path) -> None:
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    print(banner(cfg, host, data_dir), flush=True)
    print(format_status(store), flush=True)
    print(flush=True)
    try:
        while True:
            if not sys.stdin.isatty():
                thread.join()
                return
            try:
                ready, _, _ = select.select([sys.stdin], [], [], 2.0)
            except (OSError, ValueError):
                thread.join()
                return
            if not ready:
                continue
            line = sys.stdin.readline()
            if line == "":
                break
            try:
                msg = handle_cmd(store, cfg, cfg_path, line)
            except Exception as e:
                print(str(e))
                continue
            if msg == "QUIT":
                break
            if msg:
                print(msg)
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        httpd.shutdown()


def main() -> int:
    parser = argparse.ArgumentParser(description="EtherLink 市场服务（自带 SQLite）")
    parser.add_argument("--name", default=None, help="市场显示名")
    parser.add_argument("--port", type=int, default=0, help="监听端口，默认 8765")
    parser.add_argument("--token", default=None, help="插件接入令牌")
    parser.add_argument("--data", default="data", help="数据目录（含 sqlite 与 market.json）")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    parser.add_argument("--no-tui", action="store_true", help="不要命令行界面，只跑 HTTP")
    parser.add_argument("--demo", action="store_true", help="写入两台示例服和几笔成交，方便看界面")
    args = parser.parse_args()

    data_dir = Path(args.data).resolve()
    cfg = load_or_create_config(data_dir, args)
    store = Store(data_dir / "market.sqlite", cfg["name"], cfg["token"], cfg.get("fx"))
    if args.demo:
        seed_demo(store)
    Handler.store = store
    Handler.token = cfg["token"]
    Handler.cfg_path = data_dir / "market.json"
    Handler.cfg = cfg

    httpd = ThreadingHTTPServer((args.host, int(cfg["port"])), Handler)
    if args.no_tui or not sys.stdin.isatty():
        print(banner(cfg, args.host, data_dir), end="", flush=True)
        print("（后台模式，无命令行界面。浏览器打开上面的 Web。）", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("已停止")
        return 0
    run_console(httpd, store, cfg, Handler.cfg_path, args.host, data_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
