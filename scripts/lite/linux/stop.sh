#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN="$ROOT/data/run"
BACKEND_PID="$RUN/backend.pid"
FRONTEND_PID="$RUN/frontend.pid"

stop_pid() {
  local file="$1"
  local name="$2"
  if [ ! -f "$file" ]; then
    return
  fi
  local pid
  pid="$(head -n 1 "$file" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    echo "已停止${name}：$pid"
  else
    echo "${name}已经停止"
  fi
  rm -f "$file"
}

stop_pid "$BACKEND_PID" "后端"
stop_pid "$FRONTEND_PID" "前端"

# PID 文件丢失时按命令行兜底
for pid in $(ps -eo pid=,args= | grep -E "doc-canvas.jar|DocCanvas-Lite.*server.js" | grep -v grep | awk '{print $1}'); do
  kill "$pid" 2>/dev/null || true
  echo "已清理残留进程：$pid"
done

echo "Doc Canvas 已停止。"
