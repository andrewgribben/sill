# Sill Host for Windows: installs the built distribution as a logon Scheduled Task. Must run from
# an elevated PowerShell for the Scheduled Task and firewall rules; if it isn't elevated, it prints
# the exact command to re-run instead of failing partway through.
#
# Usage (from clients/windows-host after `gradlew.bat :windows-host:installDist`):
#   powershell -ExecutionPolicy Bypass -File install.ps1
#
# shortcut: no code signing and no uninstaller yet; this is phase 1's install helper, not a real
# installer. Re-run after every build to refresh %LOCALAPPDATA%\Sill\app.

param(
    [int]$Port = 7455
)

$ErrorActionPreference = "Stop"

$distDir = Join-Path $PSScriptRoot "build\install\windows-host"
if (-not (Test-Path $distDir)) {
    Write-Error "Build the distribution first: .\gradlew.bat :windows-host:installDist (looked for $distDir)"
    exit 1
}

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Not running elevated. Re-run this exact command from an elevated PowerShell:"
    Write-Host "  powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Port $Port"
    exit 1
}

$installDir = Join-Path $env:LOCALAPPDATA "Sill\app"
Write-Host "Copying the distribution to $installDir"
if (Test-Path $installDir) { Remove-Item $installDir -Recurse -Force }
Copy-Item $distDir $installDir -Recurse

# javaw, not the .bat: no console window. The log file has everything stdout had.
$javaw = (Get-Command javaw.exe -ErrorAction SilentlyContinue).Source
if (-not $javaw -and $env:JAVA_HOME) { $javaw = Join-Path $env:JAVA_HOME "bin\javaw.exe" }
if (-not $javaw -or -not (Test-Path $javaw)) { Write-Error "javaw.exe not found: install a JDK 17+ or set JAVA_HOME"; exit 1 }
$javaArgs = "-Dsun.java2d.uiScale=1 -cp `"$installDir\lib\*`" me.saffer.sill.windows.MainKt --port $Port"

# A logon task in the interactive session: capture and Robot need a real desktop, which session 0
# (services) and an SSH session do not have.
$taskName = "Sill Host"
Write-Host "Registering the Scheduled Task '$taskName'"
$action = New-ScheduledTaskAction -Execute $javaw -Argument $javaArgs
$trigger = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
# No time limit (the default stops a task after 72 hours), and started again if it ever exits.
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings | Out-Null

Write-Host "Starting '$taskName' now (stopping any copy already running)"
Get-CimInstance Win32_Process -Filter "Name='java.exe' or Name='javaw.exe'" | Where-Object CommandLine -like '*me.saffer.sill.windows*' | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-ScheduledTask -TaskName $taskName

Write-Host "Adding firewall rules for TCP $Port (the home door) and UDP 5353 (mDNS)"
Get-NetFirewallRule -DisplayName "Sill Host*" -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName "Sill Host (TCP $Port)" -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -ErrorAction SilentlyContinue | Out-Null
New-NetFirewallRule -DisplayName "Sill Host (mDNS)" -Direction Inbound -Protocol UDP -LocalPort 5353 -Action Allow -ErrorAction SilentlyContinue | Out-Null

Write-Host "Done. Sill Host runs at every logon; log at $env:LOCALAPPDATA\Sill\Sill.log"
