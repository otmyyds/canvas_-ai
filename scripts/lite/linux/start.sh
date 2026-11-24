#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$ROOT/data"
LOGS="$DATA/logs"
RUN="$DATA/run"
BACKEND_PID="$RUN/backend.pid"
FRONTEND_PID="$RUN/frontend.pid"
BACKEND_PORT="${BACKEND_PORT:-8080}"
FRONTEND_PORT="${FRONTEND_PORT:-3000}"
BACKEND_URL="http://127.0.0.1:${BACKEND_PORT}"
FRONTEND_URL="http://127.0.0.1:${FRONTEND_PORT}"

mkdir -p "$DATA" "$LOGS" "$RUN"
export DOC_CANVAS_DATA_DIR="$DATA"
export PORT="$FRONTEND_PORT"
export HOSTNAME="127.0.0.1"

if [ -z "${JWT_SECRET:-}" ]; then
  secret_file="$DATA/jwt.secret"
  if [ -f "$secret_file" ]; then
    JWT_SECRET="$(cat "$secret_file")"
  else
    JWT_SECRET="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    printf '%s' "$JWT_SECRET" >"$secret_file"
    chmod 600 "$secret_file"
  fi
  export JWT_SECRET
fi

JAVA="$ROOT/runtime/jre/bin/java"
NODE="$ROOT/runtime/node/node"
JAR="$ROOT/app/doc-canvas.jar"
WEB="$ROOT/web"

[ -x "$JAVA" ] || { echo "找不到内置 Java 运行时：$JAVA"; exit 1; }
[ -x "$NODE" ] || { echo "找不到内置 Node 运行时：$NODE"; exit 1; }
[ -f "$JAR" ] || { echo "找不到后端程序：$JAR"; exit 1; }
[ -f "$WEB/server.js" ] || { echo "找不到前端程序：$WEB/server.js"; exit 1; }

is_running() {
  local file="$1"
  [ -f "$file" ] || return 1
  local pid
  pid="$(head -n 1 "$file" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

if is_running "$BACKEND_PID" || is_running "$FRONTEND_PID"; then
  echo "Doc Canvas 已经在运行：$FRONTEND_URL"
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "$FRONTEND_URL" >/dev/null 2>&1 || true
  fi
  exit 0
fi

if ss -ltn 2>/dev/null | grep -q ":${BACKEND_PORT} "; then
  echo "端口 ${BACKEND_PORT} 已被占用"
  exit 1
fi
if ss -ltn 2>/dev/null | grep -q ":${FRONTEND_PORT} "; then
  echo "端口 ${FRONTEND_PORT} 已被占用"
  exit 1
fi

echo "正在启动 Doc Canvas..."
nohup "$JAVA" -Xms256m -Xmx640m -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -jar "$JAR" \
  --spring.profiles.active=lite \
  --server.port="$BACKEND_PORT" \
  "--app.cors.allowed-origins[0]=http://127.0.0.1:${FRONTEND_PORT}" \
  "--app.cors.allowed-origins[1]=http://localhost:${FRONTEND_PORT}" \
  >>"$LOGS/backend.log" 2>&1 &
echo $! >"$BACKEND_PID"

(
  cd "$WEB"
  nohup "$NODE" server.js >>"$LOGS/frontend.log" 2>&1 &
  echo $! >"$FRONTEND_PID"
)

ready=false
for _ in $(seq 1 90); do
  if curl -fsS --max-time 2 "$BACKEND_URL/actuator/health" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 1
done

if [ "$ready" != true ]; then
  echo "后端启动失败，日志：$LOGS/backend.log"
  kill "$(cat "$BACKEND_PID" 2>/dev/null)" 2>/dev/null || true
  kill "$(cat "$FRONTEND_PID" 2>/dev/null)" 2>/dev/null || true
  rm -f "$BACKEND_PID" "$FRONTEND_PID"
  exit 1
fi

frontend_ready=false
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$FRONTEND_URL" >/dev/null 2>&1; then
    frontend_ready=true
    break
  fi
  sleep 1
done
if [ "$frontend_ready" != true ]; then
  echo "前端启动失败，日志：$LOGS/frontend.log"
  kill "$(cat "$BACKEND_PID" 2>/dev/null)" 2>/dev/null || true
  kill "$(cat "$FRONTEND_PID" 2>/dev/null)" 2>/dev/null || true
  rm -f "$BACKEND_PID" "$FRONTEND_PID"
  exit 1
fi

if command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$FRONTEND_URL" >/dev/null 2>&1 || true
fi

echo "启动完成：$FRONTEND_URL"
echo "数据目录：$DATA"
echo "关闭程序：./stop.sh"
