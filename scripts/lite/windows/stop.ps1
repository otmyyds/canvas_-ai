$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$run = Join-Path $root "data\run"
$backendPidFile = Join-Path $run "backend.pid"
$frontendPidFile = Join-Path $run "frontend.pid"

function Stop-FromPidFile([string]$file, [string]$name) {
    if (-not (Test-Path $file)) {
        return
    }
    $value = Get-Content $file -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($value) {
        $processId = [int]$value
        try {
            Stop-Process -Id $processId -Force -ErrorAction Stop
        } catch {
        }
        Start-Sleep -Milliseconds 300
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            # Stop-Process 偶尔只结束启动器，用 taskkill 连同子进程一起清理
            & taskkill /PID $processId /T /F | Out-Null
        }
        if (Get-Process -Id $processId -ErrorAction SilentlyContinue) {
            Write-Host "$name 停止失败，请手动结束 PID $processId。"
        } else {
            Write-Host "已停止$name。"
        }
    }
    Remove-Item $file -Force -ErrorAction SilentlyContinue
}

Stop-FromPidFile $backendPidFile "后端"
Stop-FromPidFile $frontendPidFile "前端"

# PID 文件丢失时按命令行兜底，避免残留进程占用端口
Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
    Where-Object {
        $_.CommandLine -and (
            $_.CommandLine.Contains("doc-canvas.jar") -or
            $_.CommandLine.Contains("DocCanvas-Lite") -and $_.CommandLine.Contains("server.js")
        )
    } |
    ForEach-Object {
        try {
            Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop
            Write-Host "已清理残留进程：$($_.ProcessId)"
        } catch {
        }
    }

Write-Host "Doc Canvas 已停止。"
