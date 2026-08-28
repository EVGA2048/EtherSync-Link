#!/usr/bin/env python3
"""EtherLink 市场服务：自带 SQLite，插件用 HTTP 接入。无需另行安装数据库。"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

VERSION = "1.0.0"
DEFAULT_PORT = 8765
DEFAULT_NAME = "以太货栈"


def now_ms() -> int:
    return int(time.time() * 1000)


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, path: Path, name: str, token: str):
        self.path = path
        self.lock = threading.Lock()
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
                    heartbeat INTEGER NOT NULL DEFAULT 0
                );
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
                    nested_keys TEXT
                );
                CREATE INDEX IF NOT EXISTS idx_listings_server ON listings(server_code);
                CREATE INDEX IF NOT EXISTS idx_listings_seller ON listings(seller_uuid);
                """
            )
            self._meta_set("name", name)
            self._meta_set("token_hash", sha256(token))
            self._meta_set("version", VERSION)
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
        return hmac.compare_digest(expect, sha256(token))

    def info(self) -> dict:
        with self.lock:
            n = self.db.execute("SELECT COUNT(*) AS c FROM listings").fetchone()["c"]
            s = self.db.execute("SELECT COUNT(*) AS c FROM servers").fetchone()["c"]
            return {
                "ok": True,
                "name": self._meta_get("name", DEFAULT_NAME),
                "version": VERSION,
                "listings": n,
                "servers": s,
            }

    def heartbeat(self, body: dict) -> dict:
        code = str(body.get("code") or "").strip().upper()
        if not code:
            raise ValueError("缺少服务器代号")
        name = str(body.get("name") or code)
        blurb = str(body.get("blurb") or "")
        color = str(body.get("color") or "LIGHT_BLUE")
        icon = str(body.get("icon") or "TERRACOTTA")
        with self.lock:
            self.db.execute(
                """
                INSERT INTO servers(code, name, blurb, color, icon, heartbeat)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET
                    name=excluded.name,
                    blurb=excluded.blurb,
                    color=excluded.color,
                    icon=excluded.icon,
                    heartbeat=excluded.heartbeat
                """,
                (code, name, blurb, color, icon, now_ms()),
            )
            self.db.commit()
            return {"ok": True, "name": self._meta_get("name", DEFAULT_NAME)}

    def servers(self, offline_after_ms: int = 20000) -> list[dict]:
        clock = now_ms()
        with self.lock:
            rows = self.db.execute(
                "SELECT code, name, blurb, color, icon, heartbeat FROM servers ORDER BY name"
            ).fetchall()
        out = []
        for r in rows:
            age = clock - int(r["heartbeat"] or 0)
            out.append(
                {
                    "code": r["code"],
                    "name": r["name"],
                    "blurb": r["blurb"],
                    "color": r["color"],
                    "icon": r["icon"],
                    "heartbeat": r["heartbeat"],
                    "clock": clock,
                    "online": 0 <= age < offline_after_ms,
                }
            )
        return out

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
        with self.lock:
            cur = self.db.execute(
                """
                INSERT INTO listings(seller_uuid, seller_name, server_code, item_key, item_name,
                                     amount, price, created, blob_b64, nested_keys)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

    def claim(self, listing_id: int) -> dict | None:
        with self.lock:
            r = self.db.execute("SELECT * FROM listings WHERE id=?", (listing_id,)).fetchone()
            if r is None:
                return None
            self.db.execute("DELETE FROM listings WHERE id=?", (listing_id,))
            self.db.commit()
            return self._listing_dict(r)

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
    out = {"name": name, "port": port, "token": token}
    cfg_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if generated:
        print(f"已生成接入令牌，写入 {cfg_path}", file=sys.stderr)
        print(f"token = {token}", file=sys.stderr)
    return out


class Handler(BaseHTTPRequestHandler):
    store: Store
    token: str

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("[market] " + (fmt % args) + "\n")

    def _token(self) -> str:
        h = self.headers.get("Authorization") or ""
        if h.lower().startswith("bearer "):
            return h[7:].strip()
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

    def _need_auth(self) -> bool:
        if not self.store.token_ok(self._token()):
            self._send(401, {"ok": False, "error": "令牌无效"})
            return False
        return True

    def do_GET(self) -> None:
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        q = parse_qs(u.query)
        try:
            if path in ("/", "/v1/info"):
                self._send(200, self.store.info())
                return
            if path == "/health":
                self._send(200, {"ok": True})
                return
            if not self._need_auth():
                return
            if path == "/v1/servers":
                self._send(200, {"ok": True, "servers": self.store.servers()})
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
            if path == "/v1/listings":
                row = self.store.insert(body)
                self._send(200, {"ok": True, "listing": row})
                return
            if path.endswith("/claim") and path.startswith("/v1/listings/"):
                lid = int(path.split("/")[-2])
                row = self.store.claim(lid)
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


def main() -> int:
    parser = argparse.ArgumentParser(description="EtherLink 市场服务（自带 SQLite）")
    parser.add_argument("--name", default=None, help="市场显示名")
    parser.add_argument("--port", type=int, default=0, help="监听端口，默认 8765")
    parser.add_argument("--token", default=None, help="插件接入令牌")
    parser.add_argument("--data", default="data", help="数据目录（含 sqlite 与 market.json）")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址")
    args = parser.parse_args()

    data_dir = Path(args.data).resolve()
    cfg = load_or_create_config(data_dir, args)
    store = Store(data_dir / "market.sqlite", cfg["name"], cfg["token"])
    Handler.store = store
    Handler.token = cfg["token"]

    httpd = ThreadingHTTPServer((args.host, int(cfg["port"])), Handler)
    print(f"EtherLink 市场「{cfg['name']}」监听 {args.host}:{cfg['port']}")
    print(f"数据目录 {data_dir}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("已停止")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
