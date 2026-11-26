$ErrorActionPreference = 'Stop'
$candidate = $env:LEGEND_OTA_CANDIDATE
$launcher = $env:LEGEND_OTA_EXE
$staging = [IO.Path]::GetDirectoryName($candidate)
$launcherDirectory = [IO.Path]::GetDirectoryName($launcher)
$backup = Join-Path $staging 'previous.exe'
$ready = Join-Path $staging 'started'
$replaced = $false
$started = $null

try {
    $oldProcess = Get-Process -Id ([int]$env:LEGEND_OTA_PID) -ErrorAction SilentlyContinue
    if ($oldProcess -and !$oldProcess.WaitForExit(60000)) { throw 'Launcher is still running.' }
    $stream = [IO.File]::OpenRead($candidate)
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($hasher.ComputeHash($stream)).Replace('-', '') }
    finally { $stream.Dispose(); $hasher.Dispose() }
    if ($hash -ne $env:LEGEND_OTA_HASH) {
        throw 'Downloaded executable no longer matches SHA-256.'
    }
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        try {
            [IO.File]::Replace($candidate, $launcher, $backup, $true)
            $replaced = $true
            break
        } catch [IO.IOException] {
            if ($attempt -eq 19) { throw }
            Start-Sleep -Milliseconds 250
        }
    }
    $env:LEGEND_OTA_READY = $ready
    $started = Start-Process -FilePath $launcher -WorkingDirectory $launcherDirectory -WindowStyle Normal -PassThru
    for ($attempt = 0; $attempt -lt 240; $attempt++) {
        if (Test-Path -LiteralPath $ready) {
            Remove-Item -LiteralPath $backup, $ready -Force
            Remove-Item -LiteralPath $staging
            return
        }
        if ($started.HasExited) { throw 'The new launcher exited before showing its window.' }
        Start-Sleep -Milliseconds 500
    }
    throw 'Timed out waiting for the new launcher window. The previous version remains in the staging folder.'
} catch {
    $_.Exception.Message | Set-Content -LiteralPath (Join-Path $staging 'error.txt') -Encoding UTF8
    Remove-Item Env:LEGEND_OTA_READY -ErrorAction SilentlyContinue
    if ($replaced -and (!$started -or $started.HasExited) -and (Test-Path -LiteralPath $backup)) {
        [IO.File]::Replace($backup, $launcher, (Join-Path $staging 'failed.exe'), $true)
        Start-Process -FilePath $launcher -WorkingDirectory $launcherDirectory -WindowStyle Normal
    } elseif (!$replaced -and (Test-Path -LiteralPath $launcher)) {
        Start-Process -FilePath $launcher -WorkingDirectory $launcherDirectory -WindowStyle Normal
    }
}
