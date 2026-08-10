$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontend = Join-Path $root "frontend"
$envFile = Join-Path $root ".env"

Set-Location $frontend
if (Test-Path $envFile) {
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith("#")) {
            return
        }
        $parts = $line.Split("=", 2)
        if ($parts.Length -eq 2) {
            $key = $parts[0].Trim()
            $value = $parts[1].Trim()
            $existing = [Environment]::GetEnvironmentVariable($key, "Process")
            if ([string]::IsNullOrWhiteSpace($existing) -and -not [string]::IsNullOrWhiteSpace($value)) {
                [Environment]::SetEnvironmentVariable($key, $value, "Process")
            }
        }
    }
}

if (-not (Test-Path "node_modules")) {
    npm install
}
npm run dev
