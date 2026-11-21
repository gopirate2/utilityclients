#!/usr/bin/env bash
# Builds a native injector app image with a private runtime using jpackage.
#
# Output: build/exe/UtilityClient/UtilityClient.exe
# That folder is the app: the exe plus a private runtime, so the machine running
# it needs no Java installed. Zip the folder to hand it to someone.
#
# jpackage comes with the JDK (not the JRE). It needs no WiX for --type app-image;
# an .msi installer would, and the client is distributed as a zip anyway.
set -euo pipefail

cd "$(dirname "$0")/.."

# Accepts both quote styles; jpackage rejects anything that is not x.y.z, so a
# missing version would abort the whole run under `set -e`.
VERSION="$(sed -n "s/^version *= *['\"]\([0-9.]*\)['\"].*/\1/p" build.gradle | head -1)"
VERSION="${VERSION:-1.0.0}"
NAME="UtilityClient"
OUT="build/exe"
STAGE="build/exe-input"

# Prefer JAVA_HOME, then the JVM running gradle, then PATH.
find_jpackage() {
    for candidate in \
        "${JAVA_HOME:-}/bin/jpackage" \
        "${JAVA_HOME:-}/bin/jpackage.exe" \
        "$(command -v jpackage 2>/dev/null || true)" \
        "/c/Program Files/Java/jdk-25.0.2/bin/jpackage.exe" \
        "/c/Program Files/Java/jdk-23/bin/jpackage.exe"
    do
        [ -n "$candidate" ] && [ -x "$candidate" ] && { echo "$candidate"; return 0; }
    done
    # Last resort: whatever sits next to java.
    local jbin
    jbin="$(dirname "$(readlink -f "$(command -v java 2>/dev/null || echo java)")")"
    [ -x "$jbin/jpackage" ] && echo "$jbin/jpackage"
}

JPACKAGE="$(find_jpackage || true)"
if [ -z "${JPACKAGE:-}" ]; then
    echo "no jpackage found: install a JDK 17+ (jpackage ships with the JDK, not the JRE)" >&2
    echo "set JAVA_HOME to that JDK and rerun" >&2
    exit 1
fi
echo "[*] jpackage: $JPACKAGE"

echo "[*] building injector.jar"
./gradlew -p injector jar --console=plain -q

rm -rf "$STAGE" "$OUT/$NAME"
mkdir -p "$STAGE" "$OUT"
cp injector/build/libs/injector.jar "$STAGE/injector.jar"

echo "[*] packaging $NAME $VERSION"
# The runtime is jlink'd from an explicit module list instead of the default
# "every module": Swing + the HTTP client + zip + crypto is all this UI touches.
# Without it the image ships javafx-free but still java.sql, java.awt.desktop,
# jdk.jartool, compiler and friends, which is most of the 120 MB.
"$JPACKAGE" \
    --type app-image \
    --name "$NAME" \
    --app-version "$VERSION" \
    --input "$STAGE" \
    --main-jar injector.jar \
    --main-class com.baggute.cheatclient.injector.InjectorMain \
    --dest "$OUT" \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.net.http,java.prefs,jdk.attach,jdk.crypto.ec,jdk.unsupported \
    --jlink-options "--strip-debug --no-header-files --no-man-pages --compress zip-9" \
    --java-options "-Dfile.encoding=UTF-8" \
    --java-options "-Dutilityclient.launched=exe" \
    --java-options "-Xshare:auto" \
    --java-options "-XX:TieredStopAtLevel=1" \
    --description "UtilityClient injector" \
    --vendor "UtilityClient"

EXE="$OUT/$NAME/$NAME.exe"
if [ ! -f "$EXE" ]; then
    echo "packaging produced no exe at $EXE" >&2
    exit 1
fi

echo "[+] $EXE"
du -sh "$OUT/$NAME" 2>/dev/null || true
