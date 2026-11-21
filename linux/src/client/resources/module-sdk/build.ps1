param([string]$ClientJar = (Join-Path $PSScriptRoot '..\..\..\cheatclient.jar'), [string]$ExtraClasspath = '')
$ErrorActionPreference = 'Stop'
$ClientJar = (Resolve-Path -LiteralPath $ClientJar).Path
$classpath = $ClientJar
if ($ExtraClasspath) { $classpath += ';' + $ExtraClasspath }
$output = Join-Path $PSScriptRoot 'build\classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& javac --release 25 -cp $classpath -d $output (Join-Path $PSScriptRoot 'example\src\example\HelloProvider.java')
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; use a Java 25 JDK and the matching client/game classpath' }
& jar --create --file (Join-Path $PSScriptRoot '..\HelloModules.jar') -C $output . -C (Join-Path $PSScriptRoot 'example\resources') .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed' }
Write-Output 'Created HelloModules.jar. Click Reload in Modules for a new JAR; restart Minecraft after replacing a loaded one. Search for HelloCustom.'
