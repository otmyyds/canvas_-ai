param(
    [string]$BackupPath
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$data = Join-Path $root "data"
$backupDir = Join-Path $data "backups"

if (-not $BackupPath) {
    if (Test-Path $backupDir) {
        $candidates = Get-ChildItem $backupDir -Filter "*.zip" | Sort-Object LastWriteTime -Descending
        if ($candidates.Count -eq 0) {
            Write-Host "没有找到备份文件。"
            exit 1
        }
        Write-Host "可用备份："
        for ($i = 0; $i -lt $candidates.Count; $i++) {
            Write-Host ("  [{0}] {1}" -f ($i + 1), $candidates[$i].Name)
        }
        $choice = Read-Host "输入编号"
        $index = [int]$choice - 1
        if ($index -lt 0 -or $index -ge $candidates.Count) {
            Write-Host "编号无效。"
            exit 1
        }
        $BackupPath = $candidates[$index].FullName
    } else {
        Write-Host "没有找到备份目录。"
        exit 1
    }
}

if (-not (Test-Path $BackupPath)) {
    Write-Host "备份文件不存在：$BackupPath"
    exit 1
}

$confirm = Read-Host "恢复会覆盖当前 data 目录，输入 RESTORE 继续"
if ($confirm -ne "RESTORE") {
    Write-Host "已取消。"
    exit 0
}

& (Join-Path $root "stop.ps1")
Start-Sleep -Seconds 1

New-Item -ItemType Directory -Force -Path $backupDir | Out-Null
$safety = Join-Path $backupDir ("pre-restore-" + (Get-Date -Format "yyyyMMdd-HHmmss") + ".zip")
$safetyItems = @()
foreach ($name in @("doc-canvas.db", "doc-canvas.db-wal", "doc-canvas.db-shm")) {
    $path = Join-Path $data $name
    if (Test-Path $path) {
        $safetyItems += $path
    }
}
$storage = Join-Path $data "storage"
if (Test-Path $storage) {
    $safetyItems += $storage
}
if ($safetyItems.Count -gt 0) {
    Compress-Archive -Path $safetyItems -DestinationPath $safety -Force
    Write-Host "已创建恢复前备份：$safety"
}

$temp = Join-Path $data "restore-temp"
if (Test-Path $temp) {
    Remove-Item $temp -Recurse -Force
}
Expand-Archive -Path $BackupPath -DestinationPath $temp -Force
Get-ChildItem $temp -Force | ForEach-Object {
    Copy-Item $_.FullName -Destination $data -Recurse -Force
}
Remove-Item $temp -Recurse -Force

Write-Host "恢复完成。请运行 start.bat。"
