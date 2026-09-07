#!/usr/bin/env bash
# 在指定文件夹自动拉 Paper 1.21.1，配好低内存测试服。
# 默认一次部署两台（ES2:25565、SNC:25566），给 ESLink 互通用。
#
#   ./scripts/setup-paper.sh              # 仓库下 servers/es2 + servers/snc
#   ./scripts/setup-paper.sh ~/mc-test
#   ./scripts/setup-paper.sh --one ~/paper-a
#
set -euo pipefail

PAPER_VERSION="1.21.1"
HEAP="${HEAP:-768M}"
ONE=0
FORCE=0
PORT="25565"
CODE="ES2"
DEST=""

usage() {
  cat <<'EOF'
用法: setup-paper.sh [选项] [目标目录]

  默认在目标目录下创建 es2/ 与 snc/ 两台 Paper 1.21.1（端口 25565 / 25566）。
  不传目录则用当前仓库下的 servers/。

  --one          只部署一台，目标目录本身就是服务端根目录
  --heap 768M    启动堆内存（也可用环境变量 HEAP）
  --port 25565   仅 --one 时有效
  --code ES2     仅 --one 时有效，写入 ESLink 的 server.code
  --force        重新下载 Paper
  -h, --help     显示帮助

还需要本机 Java 21，以及两服共用的 MySQL（脚本不安装数据库）。
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --one) ONE=1; shift ;;
    --force) FORCE=1; shift ;;
    --heap) HEAP="${2:?}"; shift 2 ;;
    --port) PORT="${2:?}"; shift 2 ;;
    --code) CODE="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) echo "未知选项: $1" >&2; usage >&2; exit 2 ;;
    *) DEST="$1"; shift ;;
  esac
done

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "$DEST" ]; then
  DEST="$ROOT/servers"
fi
mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "缺少命令: $1" >&2
    exit 1
  }
}

need_cmd curl
need_cmd python3

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 java。请先安装 Java 21（https://adoptium.net/）" >&2
  exit 1
fi
JAVA_VER="$(java -version 2>&1 | head -n 1 || true)"
case "$JAVA_VER" in
  *'"21'*|*version\ 21*|*21.*) ;;
  *)
    echo "警告: 当前 Java 不是 21（$JAVA_VER）。Paper 1.21.1 与 ESLink 都需要 21。" >&2
    ;;
esac

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

CACHE="$DEST/.cache"
mkdir -p "$CACHE"

echo "查询 Paper $PAPER_VERSION 最新构建…"
META_JSON="$CACHE/paper-meta.json"
if ! curl -fsSL --retry 4 --retry-delay 2 \
  "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds/latest" \
  -o "$META_JSON"; then
  echo "Fill API 不可用，改走 Paper 旧接口…" >&2
  BUILDS_JSON="$CACHE/paper-builds.json"
  curl -fsSL --retry 4 --retry-delay 2 \
    "https://api.papermc.io/v2/projects/paper/versions/${PAPER_VERSION}/builds" \
    -o "$BUILDS_JSON"
  python3 - "$BUILDS_JSON" "$PAPER_VERSION" "$META_JSON" <<'PY'
import json, sys, urllib.request
builds_path, version, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
data = json.load(open(builds_path, encoding="utf-8"))
build = data["builds"][-1]["build"]
name = f"paper-{version}-{build}.jar"
url = f"https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/{name}"
sha = data["builds"][-1]["downloads"]["application"]["sha256"]
json.dump({"downloads": {"server:default": {"name": name, "url": url, "checksums": {"sha256": sha}}}}, open(out_path, "w"))
PY
fi

eval "$(python3 - "$META_JSON" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
d = data["downloads"]["server:default"]
def sh(k, v):
    v = str(v).replace("'", "'\"'\"'")
    print(f"{k}='{v}'")
sh("PAPER_URL", d["url"])
sh("PAPER_SHA", d["checksums"]["sha256"])
sh("PAPER_NAME", d["name"])
PY
)"

PAPER_JAR="$CACHE/$PAPER_NAME"
NEED_DL=1
if [ "$FORCE" -eq 0 ] && [ -f "$PAPER_JAR" ]; then
  GOT="$(sha256_file "$PAPER_JAR")"
  if [ "$GOT" = "$PAPER_SHA" ]; then
    NEED_DL=0
    echo "已有 $PAPER_NAME，跳过下载。"
  fi
fi
if [ "$NEED_DL" -eq 1 ]; then
  echo "下载 $PAPER_NAME …"
  TMP="$PAPER_JAR.part"
  curl -fL --retry 4 --retry-delay 2 -o "$TMP" "$PAPER_URL"
  GOT="$(sha256_file "$TMP")"
  if [ "$GOT" != "$PAPER_SHA" ]; then
    echo "校验失败: 期望 $PAPER_SHA，实际 $GOT" >&2
    rm -f "$TMP"
    exit 1
  fi
  mv "$TMP" "$PAPER_JAR"
fi

find_plugin_jar() {
  local f
  for f in "$ROOT/dist"/ESLink-*.jar "$ROOT/target"/ESLink-*.jar; do
    [ -f "$f" ] || continue
    case "$f" in
      *original*) continue ;;
    esac
    echo "$f"
    return 0
  done
  return 1
}

PLUGIN_JAR=""
if PLUGIN_JAR="$(find_plugin_jar)"; then
  echo "将拷贝插件 $(basename "$PLUGIN_JAR")"
else
  echo "未找到 ESLink jar。可先在仓库根目录执行: mvn -q package"
  PLUGIN_JAR=""
fi

write_server() {
  local dir="$1" port="$2" code="$3" name="$4" color="$5" blurb="$6"
  mkdir -p "$dir/plugins/ESLink" "$dir/logs"
  ln -sf "$PAPER_JAR" "$dir/paper.jar"
  printf 'eula=true\n' > "$dir/eula.txt"
  cat > "$dir/server.properties" <<EOF
motd=ESLink ${code}
server-port=${port}
online-mode=false
enforce-secure-profile=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-players=8
difficulty=peaceful
gamemode=creative
enable-command-block=true
sync-chunk-writes=false
max-world-size=29999984
level-name=world
level-type=minecraft:flat
generate-structures=false
allow-nether=false
EOF
  cat > "$dir/start.sh" <<EOF
#!/bin/sh
cd "\$(dirname "\$0")"
exec java -Xms512M -Xmx${HEAP} -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \\
  -jar paper.jar --nogui
EOF
  chmod +x "$dir/start.sh"
  if [ -n "$PLUGIN_JAR" ]; then
    cp "$PLUGIN_JAR" "$dir/plugins/$(basename "$PLUGIN_JAR")"
  fi
  local cfg="$dir/plugins/ESLink/config.yml"
  if [ ! -f "$cfg" ] && [ -f "$ROOT/src/main/resources/config.yml" ]; then
    python3 - "$ROOT/src/main/resources/config.yml" "$cfg" "$code" "$name" "$color" "$blurb" <<'PY'
import pathlib, sys
src, dest, code, name, color, blurb = sys.argv[1:7]
text = pathlib.Path(src).read_text(encoding="utf-8")
repl = {
    "  code: ES2\n": f"  code: {code}\n",
    "  short: \"\"\n": f"  short: {code}\n",
    "  name: 以太物语\n": f"  name: {name}\n",
    "  blurb: Create 机械动力 · 生存建筑\n": f"  blurb: {blurb}\n",
    "  color: LIGHT_BLUE\n": f"  color: {color}\n",
}
for a, b in repl.items():
    if a not in text:
        raise SystemExit(f"config.yml 未找到模板行: {a!r}")
    text = text.replace(a, b, 1)
pathlib.Path(dest).write_text(text, encoding="utf-8")
PY
  fi
}

if [ "$ONE" -eq 1 ]; then
  write_server "$DEST" "$PORT" "$CODE" "测试服 $CODE" "LIGHT_BLUE" "Paper 本机联调"
  echo
  echo "已部署单服: $DEST"
  echo "  端口 $PORT · 代号 $CODE · 堆内存 $HEAP"
  echo "启动:  $DEST/start.sh"
else
  write_server "$DEST/es2" 25565 ES2 "以太物语" LIGHT_BLUE "Paper 本机联调"
  write_server "$DEST/snc" 25566 SNC "测试服" LIME "Paper 本机联调"
  cat > "$DEST/start-es2.sh" <<'EOF'
#!/bin/sh
exec "$(dirname "$0")/es2/start.sh"
EOF
  cat > "$DEST/start-snc.sh" <<'EOF'
#!/bin/sh
exec "$(dirname "$0")/snc/start.sh"
EOF
  chmod +x "$DEST/start-es2.sh" "$DEST/start-snc.sh"
  echo
  echo "已部署双服: $DEST"
  echo "  ES2  → localhost:25565   堆内存 $HEAP"
  echo "  SNC  → localhost:25566"
  echo "两个终端分别运行:"
  echo "  $DEST/start-es2.sh"
  echo "  $DEST/start-snc.sh"
fi

echo
echo "下一步:"
echo "  1. 本机准备一套 MySQL，库名默认 eslink（两台服填同一套）。"
echo "  2. 编辑各服 plugins/ESLink/config.yml 的 mysql.password。"
echo "  3. 进服后 /op 自己，再 /link reload。"
if [ -z "$PLUGIN_JAR" ]; then
  echo "  4. 先 mvn -q package，再重新执行本脚本以拷入 ESLink jar。"
fi
echo "正版验证已关。6G 机器请不要在同一台再开 Youer / 整合包。"
