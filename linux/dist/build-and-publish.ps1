[CmdletBinding()]
param([string]$Jdk = $env:JAVA_HOME, [string]$Python = 'python', [switch]$SkipBuild, [string]$LinuxBundle)
$ErrorActionPreference = 'Stop'
if (-not $env:GH_TOKEN -and -not $env:GITHUB_TOKEN) { throw 'Set GH_TOKEN or GITHUB_TOKEN once in the build environment.' }
$build = @{ Jdk = $Jdk; Python = $Python; SkipBuild = $SkipBuild }
if ($LinuxBundle) { $build.LinuxBundle = $LinuxBundle }
& (Join-Path $PSScriptRoot 'build-bootstrapper.ps1') @build
$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\bootstrapper'
& $Python -B (Join-Path $PSScriptRoot 'publish-release.py') --directory $out --publish
if ($LASTEXITCODE -ne 0) { throw 'Release submission failed; successful uploads remain in a draft for retry.' }
