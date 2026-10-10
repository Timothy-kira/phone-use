# Developer loop: build the server, push it to the phone, restart it, forward a local port.
# Usage: tools\deploy.ps1 -Serial <serial> [-LocalPort 28793] [-NoBuild]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [int]$LocalPort = 28793,
    [switch]$NoBuild
)
$root = Split-Path -Parent $PSScriptRoot
if (-not $NoBuild) {
    $result = @(& "$PSScriptRoot\build.ps1" -Task ":assembleRelease")
    $result | Select-Object -First 40
    if (-not ($result[0] -match '^exit 0;')) { throw "build failed" }
}
$apk = "$root\build\outputs\apk\release\phone-use-server-release.apk"
$dir = "/data/local/tmp/phone-use"
adb -s $Serial shell "mkdir -p $dir; pkill -f kira.phoneuse.runtime.Main; sleep 0.5" | Out-Null
adb -s $Serial push $apk "$dir/server.apk" | Out-Null
adb -s $Serial shell "cd $dir && (CLASSPATH=$dir/server.apk nohup app_process / kira.phoneuse.runtime.Main $dir > server.log 2>&1 &) ; sleep 2.5; cat server.log"
adb -s $Serial forward "tcp:$LocalPort" tcp:18793 | Out-Null
try { (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$LocalPort/health" -TimeoutSec 5).Content } catch { "health failed: $_" }
