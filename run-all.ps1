# 一键启动：基础设施（compose）-> 后端 -> 前端
#
# 后端与前端各自开一个新窗口，日志分开可见、可以单独关掉；
# 具体启动逻辑复用 run-backend.ps1 / run-frontend.ps1，不在这个脚本里重复实现。
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $root "infra\docker-compose.yml"
$envFile = Join-Path $root ".env"

Set-Location $root

if (-not (Test-Path $envFile)) {
    Write-Host "缺少 .env：后端读不到数据库/Redis/对象存储/模型配置。请从 .env.example 复制一份并填好。" -ForegroundColor Red
    exit 1
}

Write-Host "==> 启动基础设施：PostgreSQL / Redis / RustFS"
docker compose -f $composeFile up -d
if ($LASTEXITCODE -ne 0) {
    Write-Host "docker compose 启动失败，请确认 Docker Desktop 已运行。" -ForegroundColor Red
    exit 1
}

# 只等带 healthcheck 的容器（rustfs 没配 healthcheck，容器起来即可用）
$healthTargets = @("doc-canvas-postgres", "doc-canvas-redis")
foreach ($container in $healthTargets) {
    Write-Host "==> 等待 $container 就绪"
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $status = docker inspect --format "{{.State.Health.Status}}" $container 2>$null
        if ($status -eq "healthy") {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        Write-Host "$container 未在 120 秒内就绪，用 docker compose -f infra/docker-compose.yml logs 看原因。" -ForegroundColor Red
        exit 1
    }
}
Write-Host "==> 基础设施就绪"

Write-Host "==> 启动后端（新窗口，首次约 20 秒）"
Start-Process powershell -ArgumentList @("-NoExit", "-NoProfile", "-File", (Join-Path $root "run-backend.ps1"))

Write-Host "==> 启动前端（新窗口，首次约 5 秒）"
Start-Process powershell -ArgumentList @("-NoExit", "-NoProfile", "-File", (Join-Path $root "run-frontend.ps1"))

Write-Host ""
Write-Host "前端       http://localhost:3000/app"
Write-Host "后端健康   http://localhost:8080/actuator/health"
Write-Host "RustFS     http://localhost:9001"
Write-Host ""
Write-Host "两个窗口各自打印日志，等后端打出 Started DocCanvasApplication 后再访问。" -ForegroundColor Green
