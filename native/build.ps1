# Builds ucstealth.dll and drops it where StealthNative.load looks for it.
#
#   powershell -ExecutionPolicy Bypass -File native\build.ps1
#   powershell -ExecutionPolicy Bypass -File native\build.ps1 -Dxgi      # D3D variant
#   powershell -ExecutionPolicy Bypass -File native\build.ps1 -NoInstall # build only
#
# Resolves the toolchain itself: CMake ships inside Visual Studio, but it is not
# on PATH in a normal shell, and neither is cl.exe without a vcvars environment.

[CmdletBinding()]
param(
    [switch]$Dxgi,
    [switch]$NoInstall,
    [ValidateSet('Release', 'Debug')]
    [string]$Configuration = 'Release'
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Find-VsWhere {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe",
        "$env:ProgramFiles\Microsoft Visual Studio\Installer\vswhere.exe"
    )
    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }
    throw 'vswhere.exe not found: install Visual Studio 2022 Build Tools.'
}

$vswhere = Find-VsWhere
$vsPath = & $vswhere -latest -products * `
    -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
    -property installationPath
if (-not $vsPath) {
    throw 'No MSVC C++ toolset found: add the "Desktop development with C++" workload.'
}

# CMake: PATH first, then the copy bundled with the IDE.
# PowerShell 5.1 compatible on purpose: this is invoked from Gradle with
# `powershell`, not `pwsh`, and the null-conditional operator (?.) is a 7.x
# feature that fails at parse time on every stock Windows install.
$cmake = $null
$onPath = Get-Command cmake.exe -ErrorAction SilentlyContinue
if ($onPath) { $cmake = $onPath.Source }
if (-not $cmake) {
    $bundled = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (Test-Path $bundled) { $cmake = $bundled }
}
if (-not $cmake) {
    throw 'cmake.exe not found: install the CMake component or put it on PATH.'
}

$buildDir = Join-Path $here 'build'
$configureArgs = @('-S', $here, '-B', $buildDir, '-A', 'x64')
if ($Dxgi) { $configureArgs += '-DUC_HOOK_DXGI=ON' }

Write-Host "==> configuring" -ForegroundColor Cyan
& $cmake @configureArgs
if ($LASTEXITCODE -ne 0) { throw "configure failed ($LASTEXITCODE)" }

Write-Host "==> building ($Configuration)" -ForegroundColor Cyan
& $cmake --build $buildDir --config $Configuration
if ($LASTEXITCODE -ne 0) { throw "build failed ($LASTEXITCODE)" }

$dll = Join-Path $buildDir "$Configuration\ucstealth.dll"
if (-not (Test-Path $dll)) { throw "ucstealth.dll was not produced at $dll" }
Write-Host "==> $dll" -ForegroundColor Green

if ($NoInstall) { return }

# Mirrors Storage.root()/native: %APPDATA%\depends\native on Windows.
$dest = Join-Path $env:APPDATA 'depends\native'
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Copy-Item $dll (Join-Path $dest 'ucstealth.dll') -Force

# Same HIDDEN+SYSTEM marking LogManager applies from Java.
$attrib = [IO.File]::GetAttributes($dest)
[IO.File]::SetAttributes($dest, $attrib -bor [IO.FileAttributes]::Hidden -bor [IO.FileAttributes]::System)
$file = Join-Path $dest 'ucstealth.dll'
[IO.File]::SetAttributes($file, [IO.File]::GetAttributes($file) -bor [IO.FileAttributes]::Hidden)

Write-Host "==> installed to $dest" -ForegroundColor Green
Write-Host 'Launch the client; stealth status in the terminal (tilde key) reports the hook.'
