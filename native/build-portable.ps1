# Builds with a portable LLVM MinGW toolchain and an already extracted MinHook.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Toolchain,
    [Parameter(Mandatory=$true)][string]$MinHook,
    [ValidateSet('Release','Debug')][string]$Configuration = 'Release'
)
$ErrorActionPreference = 'Stop'
$nativeRoot = $PSScriptRoot
$output = Join-Path $nativeRoot "build/$Configuration"
New-Item -ItemType Directory -Force -Path $output | Out-Null
$cc = Join-Path $Toolchain 'bin/clang.exe'
$cxx = Join-Path $Toolchain 'bin/clang++.exe'
if (-not (Test-Path -LiteralPath $cc)) { throw "Compiler missing: $cc" }
$include = Join-Path $MinHook 'include'
$optimization = if ($Configuration -eq 'Debug') { '-O0' } else { '-O2' }
$objects = @()
foreach ($relative in @('src/buffer.c','src/hook.c','src/trampoline.c','src/hde/hde64.c')) {
    $source = Join-Path $MinHook $relative
    $object = Join-Path $output ([IO.Path]::GetFileNameWithoutExtension($source) + '.obj')
    & $cc '--target=x86_64-w64-windows-gnu' $optimization '-I' $include '-c' $source '-o' $object
    if ($LASTEXITCODE -ne 0) { throw "Compile failed: $source" }
    $objects += $object
}
$dll = Join-Path $output 'ucstealth.dll'
& $cxx '--target=x86_64-w64-windows-gnu' '-std=c++17' '-fms-extensions' $optimization `
    '-shared' '-static' '-I' $include (Join-Path $nativeRoot 'dllmain.cpp') `
    (Join-Path $nativeRoot 'swap_hook.cpp') @objects '-lopengl32' '-lpsapi' '-o' $dll
if ($LASTEXITCODE -ne 0) { throw 'Native link failed.' }
Write-Host "BUILD SUCCESSFUL: $dll"
