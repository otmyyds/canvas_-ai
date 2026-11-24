#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="$ROOT/data"
BACKUP_DIR="$DATA/backups"
BACKUP="${1:-}"

if [ -z "$BACKUP" ]; then
  if [ ! -d "$BACKUP_DIR" ]; then
    echo "没有找到备份目录。"
    exit 1
  fi
  mapfile -t candidates < <(find "$BACKUP_DIR" -maxdepth 1 -name '*.tar.gz' | sort -r)
  if [ ${#candidates[@]} -eq 0 ]; then
    echo "没有找到备份文件。"
    exit 1
  fi
  echo "可用备份："
  for i in "${!candidates[@]}"; do
    echo "  [$((i + 1))] $(basename "${candidates[$i]}")"
  done
  read -r -p "输入编号：" choice
  index=$((choice - 1))
  if [ "$index" -lt 0 ] || [ "$index" -ge ${#candidates[@]} ]; then
    echo "编号无效。"
    exit 1
  fi
  BACKUP="${candidates[$index]}"
fi

if [ ! -f "$BACKUP" ]; then
  echo "备份文件不存在：$BACKUP"
  exit 1
fi

read -r -p "恢复会覆盖当前 data 目录，输入 RESTORE 继续：" confirm
if [ "$confirm" != "RESTORE" ]; then
  echo "已取消。"
  exit 0
fi

"$ROOT/stop.sh"
sleep 1
mkdir -p "$BACKUP_DIR"
safety="$BACKUP_DIR/pre-restore-$(date +%Y%m%d-%H%M%S).tar.gz"
items=()
for name in doc-canvas.db doc-canvas.db-wal doc-canvas.db-shm storage; do
  [ -e "$DATA/$name" ] && items+=("$name")
done
if [ ${#items[@]} -gt 0 ]; then
  tar -czf "$safety" -C "$DATA" "${items[@]}"
  echo "已创建恢复前备份：$safety"
fi

temp="$DATA/restore-temp"
rm -rf "$temp"
mkdir -p "$temp"
tar -xzf "$BACKUP" -C "$temp"
cp -a "$temp"/. "$DATA"/
rm -rf "$temp"
echo "恢复完成。请运行 ./start.sh。"
