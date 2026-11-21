# Installs a validated client JAR or a client and injector bundle.
[CmdletBinding()]
param([string]$Url = $env:UC_URL, [string]$From, [string]$ModsDir)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$root = Join-Path $env:APPDATA 'depends'
$work = Join-Path $env:TEMP ("uc-install-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
function Test-Client([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        if (-not $entry) { return $false }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json }
        finally { $reader.Dispose() }
        return $metadata.id -in @('utilityclient', 'cheatclient')
    } finally { $zip.Dispose() }
}
function Test-Injector([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { return $false }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
        return $manifest -match 'Main-Class: com.baggute.cheatclient.injector.InjectorMain' -and
            $manifest -match 'Agent-Class: com.baggute.cheatclient.injector.agent.ClientAgent'
    } finally { $zip.Dispose() }
}
try {
    Write-Host '[1/6] Checking installation source'
    if (-not $From -and -not $Url) {
        $bundles = @(Get-Item -Path (Join-Path $PSScriptRoot 'UtilityClient-*.zip') -ErrorAction SilentlyContinue)
        if ($bundles.Count -eq 1) { $From = $bundles[0].FullName }
        elseif ((Test-Path -LiteralPath (Join-Path $PSScriptRoot 'cheatclient.jar')) -and (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'injector.jar'))) {
            $From = Join-Path $work 'local.zip'
            $localStage = Join-Path $work 'local'
            New-Item -ItemType Directory -Path $localStage | Out-Null
            Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'cheatclient.jar'), (Join-Path $PSScriptRoot 'injector.jar') -Destination $localStage
            [IO.Compression.ZipFile]::CreateFromDirectory($localStage, $From)
        }
    }
    Write-Host '[2/6] Reading installation package'
    $archive = Join-Path $work 'download.zip'
    if ($From) { Copy-Item -LiteralPath $From -Destination $archive }
    elseif ($Url) { Invoke-WebRequest -Uri $Url -OutFile $archive -UseBasicParsing }
    else { throw 'Pass -From <file>, -Url <URL>, or set UC_URL.' }
    $client = Join-Path $work 'cheatclient.jar'
    $injector = Join-Path $work 'injector.jar'
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        if ($zip.GetEntry('fabric.mod.json')) {
            Copy-Item -LiteralPath $archive -Destination $client
        } else {
            foreach ($name in @('cheatclient.jar', 'injector.jar')) {
                $matches = @($zip.Entries | Where-Object { $_.Name -eq $name })
                if ($matches.Count -ne 1) { throw "Bundle requires exactly one $name" }
                [IO.Compression.ZipFileExtensions]::ExtractToFile($matches[0], (Join-Path $work $name), $true)
            }
        }
    } finally { $zip.Dispose() }
    Write-Host '[3/6] Validating client and injector'
    if (-not (Test-Client $client)) { throw 'Artifact has the wrong client mod identity.' }
    if ((Test-Path -LiteralPath $injector) -and -not (Test-Injector $injector)) { throw 'Invalid injector JAR.' }
    if ($ModsDir -and -not (Test-Path -LiteralPath $ModsDir -PathType Container)) { throw '-ModsDir must name an existing Fabric instance mods folder.' }
    Write-Host '[4/6] Installing client and injector'
    New-Item -ItemType Directory -Force -Path $root | Out-Null
    $hasInjector = (Test-Path -LiteralPath $injector -PathType Leaf) -or (Test-Injector (Join-Path $root 'injector.jar'))
    $launcher = Join-Path $root 'run.cmd'
    if ($hasInjector) {
        $launcherSource = Join-Path $work 'run.cmd'
        @'
@echo off
chcp 65001 >nul
setlocal
set "DIR=%~dp0"
set "UCJAVA="
if exist "%DIR%java.path" set /p "UCJAVA="<"%DIR%java.path"
if defined UCJAVA (
  "%UCJAVA%" -jar "%DIR%injector.jar" %*
) else if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar "%DIR%injector.jar" %*
) else (
  java -jar "%DIR%injector.jar" %*
)
set "RESULT=%ERRORLEVEL%"
endlocal & exit /b %RESULT%
'@ | ForEach-Object { [IO.File]::WriteAllText($launcherSource, $_, [Text.UTF8Encoding]::new($false)) }
    }
    $installPlan = [Collections.Generic.List[object]]::new()
    $installPlan.Add([pscustomobject]@{ Source = $client; Destination = (Join-Path $root 'cheatclient.jar') })
    if (Test-Path -LiteralPath $injector) {
        $installPlan.Add([pscustomobject]@{ Source = $injector; Destination = (Join-Path $root 'injector.jar') })
    }
    if ($ModsDir -and -not [IO.Path]::GetFullPath((Join-Path $ModsDir 'cheatclient.jar')).Equals(
            [IO.Path]::GetFullPath((Join-Path $root 'cheatclient.jar')), [StringComparison]::OrdinalIgnoreCase)) {
        $installPlan.Add([pscustomobject]@{ Source = $client; Destination = (Join-Path $ModsDir 'cheatclient.jar') })
    }
    if ($hasInjector) { $installPlan.Add([pscustomobject]@{ Source = $launcherSource; Destination = $launcher }) }
    $stagedInstalls = [Collections.Generic.List[object]]::new()
    try {
        foreach ($item in $installPlan) {
            $destinationDir = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($item.Destination))
            $suffix = [guid]::NewGuid().ToString('N')
            $stagePath = Join-Path $destinationDir ("uc-stage-" + $suffix + '.tmp')
            $backupPath = Join-Path $destinationDir ("uc-backup-" + $suffix + '.tmp')
            $existed = Test-Path -LiteralPath $item.Destination
            $stagedInstalls.Add([pscustomobject]@{ Destination = $item.Destination; Stage = $stagePath; Backup = $backupPath; Existed = $existed; Committed = $false })
            if ($existed) { Copy-Item -LiteralPath $item.Destination -Destination $backupPath }
            Copy-Item -LiteralPath $item.Source -Destination $stagePath
        }
        foreach ($item in $stagedInstalls) {
            if ($item.Existed) { [IO.File]::Replace($item.Stage, $item.Destination, $item.Backup) }
            else { [IO.File]::Move($item.Stage, $item.Destination) }
            $item.Committed = $true
        }
    } catch {
        $installFailure = $_
        $rollbackFailures = [Collections.Generic.List[string]]::new()
        for ($itemIndex = $stagedInstalls.Count - 1; $itemIndex -ge 0; $itemIndex--) {
            $item = $stagedInstalls[$itemIndex]
            if (-not $item.Committed) { continue }
            try {
                if ($item.Existed) { [IO.File]::Replace($item.Backup, $item.Destination, $item.Stage) }
                elseif (Test-Path -LiteralPath $item.Destination) { Remove-Item -LiteralPath $item.Destination -Force }
                $item.Committed = $false
            } catch { $rollbackFailures.Add($item.Backup) }
        }
        if ($rollbackFailures.Count -gt 0) {
            throw "Install failed; recovery backups retained at: $($rollbackFailures -join ', '). $installFailure"
        }
        throw $installFailure
    } finally {
        foreach ($item in $stagedInstalls) {
            if (Test-Path -LiteralPath $item.Stage) { Remove-Item -LiteralPath $item.Stage -Force }
            if (-not $item.Committed -and (Test-Path -LiteralPath $item.Backup)) { Remove-Item -LiteralPath $item.Backup -Force }
        }
    }
    foreach ($item in $stagedInstalls) {
        if (Test-Path -LiteralPath $item.Backup) { Remove-Item -LiteralPath $item.Backup -Force }
    }
    Write-Host '[5/6] Creating launcher'
    if ($hasInjector) {
        Write-Host "Installed into $root; launcher: $launcher"
    } else { Write-Host "Client installed into $root; this bare JAR contains no injector." }
    Write-Host '[6/6] Ready (Minecraft 26.2 needs Java 25, Fabric and Fabric API; menu: Right Control)'
} finally {
    $resolvedWork = [IO.Path]::GetFullPath($work)
    $resolvedTemp = [IO.Path]::GetFullPath($env:TEMP).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if ($resolvedWork.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force -ErrorAction SilentlyContinue
    }
}
