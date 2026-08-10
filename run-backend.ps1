$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mavenRepo = Join-Path $root ".m2\repository"
$envFile = Join-Path $root ".env"

Set-Location $root

# Promote User/Machine env vars into current process when Process is empty
$aiKeys = @("GPT_API_KEY", "OPENAI_API_KEY", "GPT_BASE_URL", "AI_PROVIDER", "AI_MODEL_PLANNER", "AI_MODEL_EMBEDDING", "BAILIAN_API_TOKEN")
foreach ($key in $aiKeys) {
    $processValue = [Environment]::GetEnvironmentVariable($key, "Process")
    if (-not [string]::IsNullOrWhiteSpace($processValue)) {
        continue
    }
    $userValue = [Environment]::GetEnvironmentVariable($key, "User")
    if (-not [string]::IsNullOrWhiteSpace($userValue)) {
        [Environment]::SetEnvironmentVariable($key, $userValue, "Process")
        continue
    }
    $machineValue = [Environment]::GetEnvironmentVariable($key, "Machine")
    if (-not [string]::IsNullOrWhiteSpace($machineValue)) {
        [Environment]::SetEnvironmentVariable($key, $machineValue, "Process")
    }
}

# Load .env only for keys not already set; blank values do not override
if (Test-Path $envFile) {
    Get-Content -Path $envFile -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (($line.Length -eq 0) -or $line.StartsWith("#")) {
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

$provider = [Environment]::GetEnvironmentVariable("AI_PROVIDER", "Process")
if ([string]::IsNullOrWhiteSpace($provider)) {
    $provider = "spring-ai"
}
$baseUrl = [Environment]::GetEnvironmentVariable("GPT_BASE_URL", "Process")
if ([string]::IsNullOrWhiteSpace($baseUrl)) {
    $baseUrl = "https://apinebula.ai"
}
$apiKey = [Environment]::GetEnvironmentVariable("GPT_API_KEY", "Process")
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    $apiKey = [Environment]::GetEnvironmentVariable("OPENAI_API_KEY", "Process")
}
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    $keyState = "missing"
} else {
    $keyState = "set(len=$($apiKey.Length))"
}
Write-Host "AI config: provider=$provider baseUrl=$baseUrl apiKey=$keyState"

mvn -f "backend\pom.xml" "-Dmaven.repo.local=$mavenRepo" spring-boot:run
