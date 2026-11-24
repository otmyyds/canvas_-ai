#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DATA="${DATA_DIR:-$ROOT/data}"
FORCE="${MIGRATE_FORCE:-false}"
if [ "${1:-}" = "--force" ]; then
  FORCE=true
fi

if [ -z "${MIGRATE_PG_URL:-}" ]; then
  read -r -p "PostgreSQL JDBC URL（例如 jdbc:postgresql://localhost:5432/doc_canvas）：" MIGRATE_PG_URL
fi
if [ -z "${MIGRATE_PG_USER:-}" ]; then
  read -r -p "PostgreSQL 用户名：" MIGRATE_PG_USER
fi
if [ -z "${MIGRATE_PG_PASSWORD:-}" ]; then
  read -r -s -p "PostgreSQL 密码：" MIGRATE_PG_PASSWORD
  echo
fi

JAVA="$ROOT/runtime/jre/bin/java"
JAR="$ROOT/app/doc-canvas.jar"
[ -x "$JAVA" ] || { echo "找不到内置 Java：$JAVA"; exit 1; }
[ -f "$JAR" ] || { echo "找不到后端程序：$JAR"; exit 1; }

mkdir -p "$DATA"
export DOC_CANVAS_DATA_DIR="$DATA"
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
export MIGRATE_PG_URL MIGRATE_PG_USER MIGRATE_PG_PASSWORD MIGRATE_FORCE="$FORCE"

echo "开始迁移。目标目录：$DATA"
echo "注意：本次只迁移用户、画布、节点、连线和快照；图片文件和封面不会迁移。"
"$JAVA" -Xms256m -Xmx640m -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -jar "$JAR" --spring.profiles.active=lite --server.port=0

echo "迁移完成。请运行 ./start.sh 启动 Doc Canvas。"
