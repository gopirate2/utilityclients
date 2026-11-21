#!/bin/sh
set -eu
sdk=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
client=${1:-"$sdk/../../../cheatclient.jar"}
classpath=$client
if [ -n "${2:-}" ]; then classpath="$classpath:$2"; fi
mkdir -p "$sdk/build/classes"
javac --release 25 -cp "$classpath" -d "$sdk/build/classes" "$sdk/example/src/example/HelloProvider.java"
jar --create --file "$sdk/../HelloModules.jar" -C "$sdk/build/classes" . -C "$sdk/example/resources" .
printf '%s\n' 'Created HelloModules.jar. Restart UtilityClient and search for HelloCustom.'
