#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
PACKAGE="$DIST/DocCanvas-Lite-Linux"
ARCHIVE="$DIST/DocCanvas-Lite-Linux.tar.gz"

echo "=== 构建 Doc Canvas Linux 便携版 ==="
echo "项目目录：$ROOT"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jlink" ]; then
  echo "JAVA_HOME 未设置或不是 JDK，需要 jlink"
  exit 1
fi
if ! command -v node >/dev/null 2>&1; then
  echo "找不到 node，请先安装 Node.js"
  exit 1
fi

rm -rf "$PACKAGE" "$ARCHIVE"
mkdir -p "$PACKAGE"

echo "[1/7] 构建后端 jar..."
mvn -f "$ROOT/backend/pom.xml" package -DskipTests
BACKEND_JAR="$(find "$ROOT/backend/target" -maxdepth 1 -name 'doc-canvas-*.jar' ! -name '*.original' | sort | tail -n 1)"
[ -n "$BACKEND_JAR" ] || { echo "找不到后端 jar"; exit 1; }

echo "[2/7] 构建前端..."
if [ ! -d "$ROOT/frontend/node_modules" ]; then
  npm --prefix "$ROOT/frontend" ci
fi
npm --prefix "$ROOT/frontend" run build

echo "[3/7] 组装后端和前端..."
mkdir -p "$PACKAGE/app" "$PACKAGE/web"
cp "$BACKEND_JAR" "$PACKAGE/app/doc-canvas.jar"
STANDALONE="$ROOT/frontend/.next/standalone"
[ -f "$STANDALONE/server.js" ] || { echo "Next standalone 构建产物不存在"; exit 1; }
cp -a "$STANDALONE"/. "$PACKAGE/web/"
mkdir -p "$PACKAGE/web/.next/static"
cp -a "$ROOT/frontend/.next/static"/. "$PACKAGE/web/.next/static/"
if [ -d "$ROOT/frontend/public" ]; then
  cp -a "$ROOT/frontend/public" "$PACKAGE/web/public"
fi

echo "[4/7] 生成 Linux JRE..."
"$JAVA_HOME/bin/jlink" \
  --module-path "$JAVA_HOME/jmods" \
  --add-modules ALL-MODULE-PATH \
  --strip-debug --no-header-files --no-man-pages --compress=2 \
  --output "$PACKAGE/runtime/jre"

echo "[5/7] 复制 Node 运行时..."
mkdir -p "$PACKAGE/runtime/node"
cp "$(command -v node)" "$PACKAGE/runtime/node/node"
NODE_LICENSE="$(dirname "$(command -v node)")/LICENSE"
if [ -f "$NODE_LICENSE" ]; then
  cp "$NODE_LICENSE" "$PACKAGE/runtime/node/LICENSE"
fi

echo "[6/7] 复制启动脚本和说明..."
cp -a "$ROOT/scripts/lite/linux"/. "$PACKAGE/"
chmod +x "$PACKAGE"/*.sh
cp "$ROOT/LICENSE" "$PACKAGE/LICENSE"
VERSION="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
{
  echo "Doc Canvas Lite Linux"
  echo "version: 1.0.0"
  echo "commit: $VERSION"
  echo "build: $(date '+%Y-%m-%d %H:%M:%S')"
} >"$PACKAGE/version.txt"

echo "[7/7] 生成 tar.gz..."
tar -czf "$ARCHIVE" -C "$DIST" "DocCanvas-Lite-Linux"
echo "构建完成：$ARCHIVE"
