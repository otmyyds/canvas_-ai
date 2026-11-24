#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$ROOT/data"
RUN="$DATA/run"

running=false
for file in "$RUN/backend.pid" "$RUN/frontend.pid"; do
  if [ -f "$file" ]; then
    pid="$(head -n 1 "$file" 2>/dev/null || true)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      running=true
    fi
  fi
done
if [ "$running" = true ]; then
  echo "请先运行 ./stop.sh 关闭 Doc Canvas，再执行备份。"
  exit 1
fi

mkdir -p "$DATA/backups"
stamp="$(date +%Y%m%d-%H%M%S)"
archive="$DATA/backups/doc-canvas-backup-${stamp}.tar.gz"
items=()
for name in doc-canvas.db doc-canvas.db-wal doc-canvas.db-shm storage; do
  [ -e "$DATA/$name" ] && items+=("$name")
done
if [ ${#items[@]} -eq 0 ]; then
  echo "没有可备份的数据，请先启动并使用一次 Doc Canvas。"
  exit 1
fi

tar -czf "$archive" -C "$DATA" "${items[@]}"
echo "备份完成：$archive"
