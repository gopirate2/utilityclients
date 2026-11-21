[CmdletBinding()]
param([switch]$SkipBuild, [switch]$Offline, [string]$Jdk = $env:JAVA_HOME, [string]$LinuxBundle, [string]$Python = 'python')
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$clientVersion = ((Get-Content -LiteralPath (Join-Path $project 'gradle.properties') | Where-Object { $_ -match '^version=' }) -replace '^version=','').Trim()
if ($clientVersion -notmatch '^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$') { throw 'Invalid client version in gradle.properties.' }
if (-not $Jdk) { $Jdk = 'C:\Program Files\Java\jdk-25.0.5.1' }
$jlink = Join-Path $Jdk 'bin\jlink.exe'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $jlink)) { throw 'Set -Jdk to a Windows x64 JDK 25 containing jlink.exe.' }
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The .NET Framework C# compiler is required on this build machine.' }
Push-Location $project
try {
    if (-not $SkipBuild) {
        # Distribution tasks inspect freshly built artifacts during execution.
        $arguments = @('bundle', '--console=plain', '--no-configuration-cache')
        if ($Offline) { $arguments += '--offline' }
        & .\gradlew.bat @arguments
        if ($LASTEXITCODE -ne 0) { throw 'Bundle build failed.' }
        if (-not $LinuxBundle) {
            Push-Location (Join-Path $project 'linux')
            try { & ..\gradlew.bat @arguments; if ($LASTEXITCODE -ne 0) { throw 'Linux bundle build failed.' } }
            finally { Pop-Location }
        }
    }
    if (-not $LinuxBundle) { $LinuxBundle = Join-Path $project "linux\build\dist\UtilityClient-$clientVersion.zip" }
    $LinuxBundle = [IO.Path]::GetFullPath($LinuxBundle)
    if (-not (Test-Path -LiteralPath $LinuxBundle)) { throw 'Build the matching Linux bundle, or pass -LinuxBundle to its verified ZIP.' }
    $out = Join-Path $project 'build\bootstrapper'
    New-Item -ItemType Directory -Force -Path $out | Out-Null
    $work = Join-Path $out ('package-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        Write-Host '[1/4] Staging client and injector'
        & $compiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$(Join-Path $work 'UtilityClient.exe')" "/win32icon:$(Join-Path $PSScriptRoot 'icons\utilityclient.ico')" /reference:System.Windows.Forms.dll (Join-Path $PSScriptRoot 'WindowsLauncher.cs') (Join-Path $PSScriptRoot 'WindowsShortcuts.cs')
        if ($LASTEXITCODE -ne 0) { throw 'Windows launcher compile failed.' }
        Copy-Item -LiteralPath (Join-Path $project "build\libs\cheatclient-$clientVersion.jar") -Destination (Join-Path $work 'cheatclient.jar')
        Copy-Item -LiteralPath (Join-Path $project 'injector\build\libs\injector.jar') -Destination (Join-Path $work 'injector.jar')
        $baritoneNotices = Join-Path $work 'third-party\baritone'
        New-Item -ItemType Directory -Force -Path $baritoneNotices | Out-Null
        foreach ($notice in @('LICENSE-Baritone.txt', 'README.md', 'baritone-1.19.0-source.zip')) {
            Copy-Item -LiteralPath (Join-Path (Join-Path $project 'vendor') $notice) -Destination $baritoneNotices
        }
        Write-Host '[2/4] Building private Java runtime'
        & $jlink --add-modules java.base,java.desktop,java.instrument,java.logging,java.net.http,java.prefs,jdk.attach,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --compress zip-9 --output (Join-Path $work 'runtime')
        if ($LASTEXITCODE -ne 0) { throw 'Runtime build failed.' }
        if ($PSVersionTable.PSVersion.Major -lt 6) {
            Add-Type -Path (Join-Path $PSScriptRoot 'RuntimeManifest.cs') -ReferencedAssemblies System.dll,System.Core.dll,System.Xml.dll
        } else {
            Add-Type -Path (Join-Path $PSScriptRoot 'RuntimeManifest.cs')
        }
        [UtilityClientRuntimeManifest]::Patch((Join-Path $work 'runtime\bin\java.exe'))
        [UtilityClientRuntimeManifest]::Patch((Join-Path $work 'runtime\bin\javaw.exe'))
        $buildId = (Get-FileHash -LiteralPath (Join-Path $work 'cheatclient.jar') -Algorithm SHA256).Hash.Substring(0,16).ToLowerInvariant()
        & $Python (Join-Path $PSScriptRoot 'package-release.py') manifest $work --build $buildId --version $clientVersion
        if ($LASTEXITCODE -ne 0) { throw 'Package manifest creation failed.' }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $payload = Join-Path $out 'Payload.zip'
        $pendingPayload = Join-Path $out ('Payload-' + [guid]::NewGuid().ToString('N') + '.zip')
        [IO.Compression.ZipFile]::CreateFromDirectory($work, $pendingPayload, [IO.Compression.CompressionLevel]::Optimal, $false)
        Move-Item -LiteralPath $pendingPayload -Destination $payload -Force
        $hashFile = Join-Path $out 'Payload.sha256'
        (Get-FileHash -LiteralPath $payload -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath $hashFile -Encoding ASCII
        $linuxPayload = Join-Path $out 'LinuxBundle.zip'
        Copy-Item -LiteralPath $LinuxBundle -Destination $linuxPayload -Force
        $linuxHash = Join-Path $out 'LinuxBundle.sha256'
        (Get-FileHash -LiteralPath $linuxPayload -Algorithm SHA256).Hash.ToLowerInvariant() | Set-Content -LiteralPath $linuxHash -Encoding ASCII
        & $Python (Join-Path $PSScriptRoot 'package-release.py') update --payload $payload --linux $linuxPayload --output $out --build $buildId --version $clientVersion
        if ($LASTEXITCODE -ne 0) { throw 'Update package creation or Linux integrity check failed.' }
        Write-Host '[3/4] Compiling Windows bootstrapper'
        $exe = Join-Path $work 'UtilityClient-Setup.exe'
        & $compiler /nologo /target:winexe /platform:x64 /optimize+ "/out:$exe" "/win32icon:$(Join-Path $PSScriptRoot 'icons\bootstrapper.ico')" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll /reference:System.Web.Extensions.dll "/resource:$payload,Payload.zip" "/resource:$hashFile,Payload.sha256" "/resource:$linuxPayload,LinuxBundle.zip" "/resource:$linuxHash,LinuxBundle.sha256" "/resource:$(Join-Path $PSScriptRoot 'icons\bootstrapper.ico'),Bootstrapper.ico" "/resource:$(Join-Path $PSScriptRoot 'icons\bootstrapper.png'),Bootstrapper.png" (Join-Path $PSScriptRoot 'Bootstrapper.cs') (Join-Path $PSScriptRoot 'BootstrapperMaintenance.cs') (Join-Path $PSScriptRoot 'BootstrapperUpdates.cs') (Join-Path $PSScriptRoot 'BootstrapperManagementUi.cs') (Join-Path $PSScriptRoot 'WindowsShortcuts.cs') (Join-Path $PSScriptRoot 'LaunchOptions.cs') (Join-Path $PSScriptRoot 'BootstrapperLaunchUi.cs') (Join-Path $PSScriptRoot 'AppBrand.cs') (Join-Path $PSScriptRoot 'BootstrapperUi.cs')
        if ($LASTEXITCODE -ne 0) { throw 'Bootstrapper compile failed.' }
        $updated = Join-Path $out 'UtilityClient-Setup-Updated.exe'
        Copy-Item -LiteralPath $exe -Destination $updated -Force
        $standard = Join-Path $out 'UtilityClient-Setup.exe'
        try { Copy-Item -LiteralPath $exe -Destination $standard -Force }
        catch [System.IO.IOException] { Write-Warning "The standard setup is open; the updated setup was built at $updated." }
        ((Get-FileHash -LiteralPath $updated -Algorithm SHA256).Hash.ToLowerInvariant() + '  UtilityClient-Setup-Updated.exe') | Set-Content -LiteralPath ($updated + '.sha256') -Encoding ASCII
        Write-Host "[4/4] Ready: $updated"
    } finally {
        $resolved = [IO.Path]::GetFullPath($work)
        $boundary = [IO.Path]::GetFullPath($out).TrimEnd('\') + '\'
        if ($resolved.StartsWith($boundary, [StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $resolved -Recurse -Force }
    }
} finally { Pop-Location }
