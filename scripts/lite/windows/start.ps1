param(
    [switch]$NoBrowser,
    [int]$FrontendPort = 3000
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$data = Join-Path $root "data"
$logs = Join-Path $data "logs"
$run = Join-Path $data "run"
$backendPidFile = Join-Path $run "backend.pid"
$frontendPidFile = Join-Path $run "frontend.pid"
$backendUrl = "http://127.0.0.1:8080"
$frontendUrl = "http://127.0.0.1:$FrontendPort"

New-Item -ItemType Directory -Force -Path $data, $logs, $run | Out-Null
$env:DOC_CANVAS_DATA_DIR = $data
$env:PORT = "$FrontendPort"
$env:HOSTNAME = "127.0.0.1"

# 首次启动生成本机 JWT 密钥并持久化，避免用户配置环境变量
if (-not $env:JWT_SECRET) {
    $secretFile = Join-Path $data "jwt.secret"
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

function Test-PortInUse([int]$port) {
    try {
        return $null -ne (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction Stop |
            Select-Object -First 1)
    } catch {
        return $false
    }
}

function Test-PidFile([string]$file) {
    if (-not (Test-Path $file)) {
        return $false
    }
    $value = Get-Content $file -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $value) {
        return $false
    }
    return $null -ne (Get-Process -Id ([int]$value) -ErrorAction SilentlyContinue)
}

if ((Test-PidFile $backendPidFile) -or (Test-PidFile $frontendPidFile)) {
    Write-Host "Doc Canvas 已经在运行。"
    Write-Host "如果页面没有打开，请访问 $frontendUrl"
    if (-not $NoBrowser) {
        Start-Process $frontendUrl
    }
    exit 0
}

if (Test-PortInUse 8080) {
    Write-Host "端口 8080 已被占用，无法启动后端。请关闭占用该端口的程序后重试。"
    exit 1
}
if (Test-PortInUse $FrontendPort) {
    Write-Host "端口 $FrontendPort 已被占用，无法启动前端。请关闭占用该端口的程序后重试。"
    exit 1
}

$java = Join-Path $root "runtime\jre\bin\java.exe"
$node = Join-Path $root "runtime\node\node.exe"
$jar = Join-Path $root "app\doc-canvas.jar"
$web = Join-Path $root "web"

if (-not (Test-Path $java)) { Write-Host "找不到内置 Java 运行时：$java"; exit 1 }
if (-not (Test-Path $node)) { Write-Host "找不到内置 Node 运行时：$node"; exit 1 }
if (-not (Test-Path $jar)) { Write-Host "找不到后端程序：$jar"; exit 1 }
if (-not (Test-Path (Join-Path $web "server.js"))) { Write-Host "找不到前端程序：$web\server.js"; exit 1 }

Write-Host "正在启动 Doc Canvas..."
$backend = Start-Process -FilePath $java `
    -ArgumentList @("-Xms256m", "-Xmx640m", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
        "-jar", $jar, "--spring.profiles.active=lite", "--server.port=8080",
        "--app.cors.allowed-origins[0]=http://127.0.0.1:$FrontendPort",
        "--app.cors.allowed-origins[1]=http://localhost:$FrontendPort") `
    -WorkingDirectory $root `
    -RedirectStandardOutput (Join-Path $logs "backend.log") `
    -RedirectStandardError (Join-Path $logs "backend.err.log") `
    -WindowStyle Hidden -PassThru
$backend.Id | Set-Content $backendPidFile

$frontend = Start-Process -FilePath $node `
    -ArgumentList @("server.js") `
    -WorkingDirectory $web `
    -RedirectStandardOutput (Join-Path $logs "frontend.log") `
    -RedirectStandardError (Join-Path $logs "frontend.err.log") `
    -WindowStyle Hidden -PassThru
$frontend.Id | Set-Content $frontendPidFile

$backendReady = $false
for ($i = 0; $i -lt 90; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri "$backendUrl/actuator/health" -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $backendReady = $true
            break
        }
    } catch {
    }
    Start-Sleep -Seconds 1
}

if (-not $backendReady) {
    Write-Host "后端启动失败。日志：$logs\backend.log"
    Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $backendPidFile, $frontendPidFile -Force -ErrorAction SilentlyContinue
    exit 1
}

$frontendReady = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $frontendUrl -TimeoutSec 2
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 500) {
            $frontendReady = $true
            break
        }
    } catch {
    }
    Start-Sleep -Seconds 1
}

if (-not $frontendReady) {
    Write-Host "前端启动失败。日志：$logs\frontend.log"
    Stop-Process -Id $backend.Id -Force -ErrorAction SilentlyContinue
    Stop-Process -Id $frontend.Id -Force -ErrorAction SilentlyContinue
    Remove-Item $backendPidFile, $frontendPidFile -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "启动完成：$frontendUrl"
Write-Host "数据目录：$data"
Write-Host "关闭程序请运行 stop.bat"
if (-not $NoBrowser) {
    Start-Process $frontendUrl
}
