$ErrorActionPreference = 'Stop'
$routerRoot = $PSScriptRoot
$pythonExe = Join-Path $routerRoot '.venv\Scripts\python.exe'
$logsDir = Join-Path $routerRoot 'logs'
New-Item -ItemType Directory -Path $logsDir -Force | Out-Null
try {
    $existing = Invoke-RestMethod -Uri 'http://127.0.0.1:8010/health' -TimeoutSec 2
    if ($existing.status -eq 'ready') {
        Write-Output 'Service is already ready: http://127.0.0.1:8010/docs'
        exit 0
    }
} catch { }
$routerProcess = Start-Process -FilePath $pythonExe -ArgumentList 'server.py' -WorkingDirectory $routerRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logsDir 'service.stdout.log') -RedirectStandardError (Join-Path $logsDir 'service.stderr.log') -PassThru
$routerProcess.Id | Set-Content -LiteralPath (Join-Path $routerRoot 'service.pid') -Encoding ascii
Write-Output "Service starting, PID $($routerProcess.Id). Logs: $logsDir"
