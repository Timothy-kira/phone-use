# Build the phone-side server. Usage: tools\build.ps1 [-Task assembleRelease] [-Log path]
# Prints the compile errors grouped by file, so a port can be driven by what the compiler reports.
param(
    [string]$Task = ":compileDebugKotlin",
    [string]$Log = "$env:TEMP\phone-use-server-build.log"
)
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$root = Split-Path -Parent $PSScriptRoot
$p = Start-Process -FilePath "$root\gradlew.bat" -ArgumentList $Task, "--console=plain" `
    -WorkingDirectory $root -RedirectStandardOutput "$Log.out" -RedirectStandardError "$Log.err" `
    -NoNewWindow -PassThru
$null = $p.Handle  # keeps the handle so ExitCode is readable after the process ends
# Not -Wait: that also waits for the Gradle daemon the build leaves running, which never exits.
$p.WaitForExit()
$errors = Select-String -Path "$Log.out", "$Log.err" -Pattern '^e: ' | ForEach-Object { $_.Line }
"exit $($p.ExitCode); errors: $($errors.Count)"
if ($p.ExitCode -ne 0 -and $errors.Count -eq 0) { Get-Content "$Log.err" -Tail 25 }
$errors | ForEach-Object { $_ -replace '^e: file:///C:/Users/Akira/claude-plugins/phone-use/server/src/main/java/kira/phoneuse/', '' }
if ($p.ExitCode -ne 0) { throw "Phone Use build failed (exit $($p.ExitCode)); no artifact may be installed." }
