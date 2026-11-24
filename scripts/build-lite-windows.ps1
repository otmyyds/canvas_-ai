$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$distRoot = Join-Path $root "dist"
$packageDir = Join-Path $distRoot "DocCanvas-Lite-Windows"
$zipPath = Join-Path $distRoot "DocCanvas-Lite-Windows.zip"

Write-Host "=== 构建 Doc Canvas Windows 便携版 ==="
Write-Host "项目目录：$root"

$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\jlink.exe"))) {
    throw "JAVA_HOME 未设置或不是 JDK 21，需要 jlink.exe"
}
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if (-not $nodeCommand) {
    throw "找不到 node，请先安装 Node.js"
}

if (Test-Path $packageDir) {
    Remove-Item $packageDir -Recurse -Force
}
if (Test-Path $zipPath) {
    Remove-Item $zipPath -Force
}
New-Item -ItemType Directory -Force -Path $packageDir | Out-Null

Write-Host "[1/7] 构建后端 jar..."
& mvn -f (Join-Path $root "backend\pom.xml") package -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "后端构建失败"
}

$backendJar = Get-ChildItem (Join-Path $root "backend\target") -Filter "doc-canvas-*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $backendJar) {
    throw "找不到后端 jar"
}

Write-Host "[2/7] 构建前端..."
if (-not (Test-Path (Join-Path $root "frontend\node_modules"))) {
    & npm --prefix (Join-Path $root "frontend") ci
    if ($LASTEXITCODE -ne 0) {
        throw "前端依赖安装失败"
    }
}
& npm --prefix (Join-Path $root "frontend") run build
if ($LASTEXITCODE -ne 0) {
    throw "前端构建失败"
}

Write-Host "[3/7] 组装后端和前端..."
$appDir = Join-Path $packageDir "app"
$webDir = Join-Path $packageDir "web"
New-Item -ItemType Directory -Force -Path $appDir, $webDir | Out-Null
Copy-Item $backendJar.FullName (Join-Path $appDir "doc-canvas.jar") -Force

$standalone = Join-Path $root "frontend\.next\standalone"
$static = Join-Path $root "frontend\.next\static"
$public = Join-Path $root "frontend\public"
if (-not (Test-Path (Join-Path $standalone "server.js"))) {
    throw "Next standalone 构建产物不存在"
}
Copy-Item (Join-Path $standalone "*") $webDir -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $webDir ".next\static") | Out-Null
Copy-Item (Join-Path $static "*") (Join-Path $webDir ".next\static") -Recurse -Force
if (Test-Path $public) {
    Copy-Item $public $webDir -Recurse -Force
}

Write-Host "[4/7] 生成 Windows JRE..."
$jreDir = Join-Path $packageDir "runtime\jre"
& (Join-Path $javaHome "bin\jlink.exe") `
    --module-path (Join-Path $javaHome "jmods") `
    --add-modules ALL-MODULE-PATH `
    --strip-debug --no-header-files --no-man-pages --compress=2 `
    --output $jreDir
if ($LASTEXITCODE -ne 0) {
    throw "jlink 生成 JRE 失败"
}

Write-Host "[5/7] 复制 Node 运行时..."
$nodeDir = Join-Path $packageDir "runtime\node"
New-Item -ItemType Directory -Force -Path $nodeDir | Out-Null
Copy-Item $nodeCommand.Source (Join-Path $nodeDir "node.exe") -Force
$nodeLicense = Join-Path (Split-Path -Parent $nodeCommand.Source) "LICENSE"
if (Test-Path $nodeLicense) {
    Copy-Item $nodeLicense (Join-Path $nodeDir "LICENSE") -Force
}

Write-Host "[6/7] 复制启动脚本和说明..."
Copy-Item (Join-Path $PSScriptRoot "lite\windows\*") $packageDir -Recurse -Force
Copy-Item (Join-Path $root "LICENSE") (Join-Path $packageDir "LICENSE") -Force
$version = (& git -C $root rev-parse --short HEAD 2>$null)
if (-not $version) {
    $version = "unknown"
}
@"
Doc Canvas Lite Windows
version: 1.0.0
commit: $version
build: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
"@ | Set-Content (Join-Path $packageDir "version.txt") -Encoding UTF8

Write-Host "[7/7] 生成 ZIP..."
Compress-Archive -Path (Join-Path $packageDir "*") -DestinationPath $zipPath -Force
$sizeMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
Write-Host "构建完成：$zipPath（$sizeMb MB）"
