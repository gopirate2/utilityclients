#!/usr/bin/env bash
# Install a local distribution, unpacked distribution, or HTTPS release bundle.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
URL="${UC_URL:-}"; FROM=""; MODS=""; runtime=auto; desktop=1; offline=0; stage=1
usage() {
    cat <<'HELP'
Usage: bash install.sh [--from FILE_OR_FOLDER | --url HTTPS_URL] [options]
  --mods-dir PATH  Copy client into this existing Fabric instance mods folder
  --offline        Disable downloads (requires existing Java 25 for injector)
  --no-runtime     Install files only; configure JAVA_HOME before launching
  --download-java  Provision private Temurin JDK 25 if Java is missing (default)
  --no-desktop     Skip the Linux application-menu entry
With no source, uses adjacent cheatclient.jar + injector.jar or a unique ZIP.
Requires Bash and Python 3.8+. No sudo; settings are preserved.
HELP
}
value() { [[ $# -ge 2 && -n "$2" ]] || { echo "Missing value for $1" >&2; exit 2; }; }
while [[ $# -gt 0 ]]; do
    case "$1" in
        --from) value "$@"; FROM="$2"; shift 2 ;;
        --url) value "$@"; URL="$2"; shift 2 ;;
        --mods-dir) value "$@"; MODS="$2"; shift 2 ;;
        --offline) offline=1; shift ;;
        --no-runtime) runtime=none; shift ;;
        --download-java) runtime=auto; shift ;;
        --no-desktop) desktop=0; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; exit 2 ;;
    esac
done
trap 'echo "Installation failed during stage $stage/6. Review the error above; settings are preserved." >&2' ERR
step() { stage="$1"; printf '[%s/6] %s\n' "$1" "$2"; }
step 1 'Checking dependencies and source'
PYTHON="$(command -v python3 || command -v python || true)"
[[ -n "$PYTHON" ]] || { echo 'Python 3.8+ required. Install python3 using your distribution package manager.' >&2; exit 1; }
"$PYTHON" -c 'import sys; assert sys.version_info >= (3,8), "Python 3.8+ required"'
[[ -f "$here/install-artifacts.py" ]] || { echo 'Keep install.sh and its Python helpers in the same folder.' >&2; exit 1; }
if [[ -z "$FROM" && -z "$URL" ]]; then
    if [[ -f "$here/cheatclient.jar" && -f "$here/injector.jar" ]]; then FROM="$here"
    else
        shopt -s nullglob
        bundles=("$here"/UtilityClient-*.zip "$here/../build/dist/"UtilityClient-*.zip)
        [[ ${#bundles[@]} -eq 1 ]] || { usage >&2; echo 'Pass --from when no unique local bundle exists.' >&2; exit 2; }
        FROM="${bundles[0]}"
    fi
fi
ROOT="${XDG_CONFIG_HOME:-$HOME/.config}/depends"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"
mkdir -p "$ROOT"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
step 2 'Reading installation package'
if [[ -z "$FROM" ]]; then
    [[ $offline -eq 0 ]] || { echo '--offline cannot download --url' >&2; exit 2; }
    [[ "$URL" == https://* ]] || { echo '--url requires HTTPS' >&2; exit 2; }
    FROM="$work/bundle.zip"
    "$PYTHON" - "$URL" "$FROM" <<'DOWNLOAD'
import sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=60) as source, open(sys.argv[2], 'wb') as target:
    if not source.url.startswith('https://'): raise ValueError('Redirect requires HTTPS')
    total = 0
    while True:
        chunk = source.read(1024 * 1024)
        if not chunk: break
        total += len(chunk)
        if total > 512 * 1024 * 1024: raise ValueError('Bundle exceeds 512 MiB')
        target.write(chunk)
DOWNLOAD
fi
[[ -e "$FROM" ]] || { echo "No source at $FROM" >&2; exit 1; }
package="$("$PYTHON" "$here/install-artifacts.py" validate "$FROM")"
JAVA=""
valid_java() {
    local candidate="$1" version modules
    [[ -n "$candidate" ]] && command -v "$candidate" >/dev/null 2>&1 || return 1
    version="$("$candidate" -version 2>&1)" || return 1
    version="$(printf '%s\n' "$version" | sed -n 's/^\(openjdk\|java\) version "\([0-9][0-9]*\).*/\2/p')"
    [[ "$version" =~ ^[0-9]+$ && "$version" -ge 25 ]] || return 1
    modules="$("$candidate" --list-modules 2>/dev/null)" || return 1
    [[ "$modules" == *jdk.attach@* && "$modules" == *java.desktop@* ]]
}
step 3 'Checking Java 25 and attach support'
if [[ "$package" == injector=yes ]]; then
    cached=""; [[ ! -f "$ROOT/runtime.path" ]] || cached="$(cat "$ROOT/runtime.path")"
    for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$cached" java; do
        if valid_java "$candidate"; then JAVA="$(command -v "$candidate")"; break; fi
    done
    if [[ -z "$JAVA" && "$runtime" != none ]]; then
        [[ $offline -eq 0 ]] || { echo 'Offline install needs Java 25 with jdk.attach, or use --no-runtime to install files only.' >&2; exit 1; }
        echo 'Downloading a verified private Temurin JDK 25 (no sudo)...'
        java_home="$("$PYTHON" "$here/java-runtime.py" "$DATA/utilityclient/runtimes")"
        JAVA="$java_home/bin/java"
        valid_java "$JAVA" || { echo 'Downloaded runtime failed Java/attach validation.' >&2; exit 1; }
    fi
fi
if [[ "$package" == injector=yes ]]; then
    cat > "$work/run.sh" <<'LAUNCH'
#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA=""; cached=""; [[ ! -f "$DIR/runtime.path" ]] || cached="$(cat "$DIR/runtime.path")"
for candidate in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$cached" java; do
    [[ -n "$candidate" ]] && command -v "$candidate" >/dev/null 2>&1 || continue
    version="$("$candidate" -version 2>&1)" || continue
    major="$(printf '%s\n' "$version" | sed -n 's/^\(openjdk\|java\) version "\([0-9][0-9]*\).*/\2/p')"
    [[ "$major" =~ ^[0-9]+$ && "$major" -ge 25 ]] || continue
    modules="$("$candidate" --list-modules 2>/dev/null)" || continue
    [[ "$modules" == *jdk.attach@* && "$modules" == *java.desktop@* ]] || continue
    JAVA="$candidate"; break
done
if [[ -z "$JAVA" ]]; then
    message='UtilityClient needs Java 25 with jdk.attach. Set JAVA_HOME or rerun the installer to provision a private runtime.'
    echo "$message" >&2
    if command -v zenity >/dev/null 2>&1; then zenity --error --text="$message" || true; fi
    exit 1
fi
exec "$JAVA" -jar "$DIR/injector.jar" "$@"
LAUNCH
    chmod +x "$work/run.sh"
fi
step 4 'Validating and installing client and injector'
args=(install "$FROM" "$ROOT")
[[ -z "$MODS" ]] || args+=(--mods-dir "$MODS")
if [[ "$package" == injector=yes ]]; then
    args+=(--launcher "$work/run.sh")
    [[ -z "$JAVA" ]] || args+=(--java "$JAVA")
fi
package="$("$PYTHON" "$here/install-artifacts.py" "${args[@]}")"
step 5 'Creating launchers'
if [[ "$package" == injector=yes ]]; then
    if [[ $desktop -eq 1 && "$(uname -s)" == Linux ]]; then
        "$PYTHON" "$here/install-artifacts.py" desktop "$ROOT" "$DATA"
        if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$DATA/applications" >/dev/null 2>&1 || true; fi
    fi
fi
step 6 'Ready'
echo "Installed: $ROOT"
if [[ "$package" == injector=yes ]]; then echo "Open UtilityClient from the application menu or run: \"$ROOT/run.sh\""; fi
if [[ -n "$MODS" ]]; then echo "Client copied to $MODS/cheatclient.jar. Start Minecraft 26.2 with Fabric and Fabric API; Right Control opens the menu."; fi
