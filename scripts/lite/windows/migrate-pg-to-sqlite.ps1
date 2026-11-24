param(
    [string]$PgUrl,
    [string]$PgUser,
    [string]$PgPassword,
    [string]$DataDir = (Join-Path $PSScriptRoot "data"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$run = Join-Path $DataDir "run"

function Test-Running([string]$file) {
    if (-not (Test-Path $file)) {
        return $false
    }
    $value = Get-Content $file -ErrorAction SilentlyContinue | Select-Object -First 1
    return $value -and $null -ne (Get-Process -Id ([int]$value) -ErrorAction SilentlyContinue)
}

if ((Test-Running (Join-Path $run "backend.pid")) -or (Test-Running (Join-Path $run "frontend.pid"))) {
    Write-Host "请先运行 stop.bat 关闭 Doc Canvas，再执行迁移。"
    exit 1
}

if (-not $PgUrl) {
    $PgUrl = Read-Host "PostgreSQL JDBC URL（例如 jdbc:postgresql://localhost:5432/doc_canvas）"
}
if (-not $PgUser) {
    $PgUser = Read-Host "PostgreSQL 用户名"
}
if (-not $PgPassword) {
    $secure = Read-Host "PostgreSQL 密码" -AsSecureString
    $PgPassword = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
}

$java = Join-Path $root "runtime\jre\bin\java.exe"
$jar = Join-Path $root "app\doc-canvas.jar"
if (-not (Test-Path $java)) { Write-Host "找不到内置 Java：$java"; exit 1 }
if (-not (Test-Path $jar)) { Write-Host "找不到后端程序：$jar"; exit 1 }

$env:DOC_CANVAS_DATA_DIR = $DataDir
New-Item -ItemType Directory -Force -Path $DataDir | Out-Null
if (-not $env:JWT_SECRET) {
    $secretFile = Join-Path $DataDir "jwt.secret"
    if (Test-Path $secretFile) {
        $env:JWT_SECRET = (Get-Content $secretFile -Raw).Trim()
    } else {
        $bytes = New-Object byte[] 32
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $rng.GetBytes($bytes)
        $hex = -join ($bytes | ForEach-Object { $_.ToString("x2") })
        Set-Content -Path $secretFile -Value $hex -NoNewline
        $env:JWT_SECRET = $hex
    }
}
$env:MIGRATE_PG_URL = $PgUrl
$env:MIGRATE_PG_USER = $PgUser
$env:MIGRATE_PG_PASSWORD = $PgPassword
if ($Force) {
    $env:MIGRATE_FORCE = "true"
}

Write-Host "开始迁移。目标目录：$DataDir"
Write-Host "注意：本次只迁移用户、画布、节点、连线和快照；图片文件和封面不会迁移。"
$javaArgs = @(
    "-Xms256m",
    "-Xmx640m",
    "-Djava.awt.headless=true",
    "-Dfile.encoding=UTF-8",
    "-jar", $jar,
    "--spring.profiles.active=lite",
    "--server.port=0"
)
& $java @javaArgs
$code = $LASTEXITCODE
if ($code -ne 0) {
    Write-Host "迁移失败，退出码：$code"
    exit $code
}
Write-Host "迁移完成。请运行 start.bat 启动 Doc Canvas。"
