$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$data = Join-Path $root "data"
$run = Join-Path $data "run"

function Test-Running([string]$file) {
    if (-not (Test-Path $file)) {
        return $false
    }
    $value = Get-Content $file -ErrorAction SilentlyContinue | Select-Object -First 1
    return $value -and $null -ne (Get-Process -Id ([int]$value) -ErrorAction SilentlyContinue)
}

if ((Test-Running (Join-Path $run "backend.pid")) -or (Test-Running (Join-Path $run "frontend.pid"))) {
    Write-Host "请先运行 stop.bat 关闭 Doc Canvas，再执行备份。"
    exit 1
}

$backupDir = Join-Path $data "backups"
New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$zipPath = Join-Path $backupDir "doc-canvas-backup-$timestamp.zip"

$items = @()
foreach ($name in @("doc-canvas.db", "doc-canvas.db-wal", "doc-canvas.db-shm")) {
    $path = Join-Path $data $name
    if (Test-Path $path) {
        $items += $path
    }
}
$storage = Join-Path $data "storage"
if (Test-Path $storage) {
    $items += $storage
}
if ($items.Count -eq 0) {
    Write-Host "没有可备份的数据，请先启动并使用一次 Doc Canvas。"
    exit 1
}

Compress-Archive -Path $items -DestinationPath $zipPath -Force
Write-Host "备份完成：$zipPath"
