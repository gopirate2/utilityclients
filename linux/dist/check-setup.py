"""Installer integration checks. Every installed file stays in a disposable workspace folder."""
from pathlib import Path
import ctypes
import hashlib
import io
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import tarfile
import json
import zipfile

ROOT = Path(__file__).resolve().parent.parent
WORK = Path(tempfile.mkdtemp(prefix='setup-check-', dir=ROOT / 'build'))
BUNDLE = ROOT / 'build/dist/UtilityClient-1.0.0.zip'
CLIENT = ROOT / 'build/libs/cheatclient-1.0.0.jar'
INJECTOR = ROOT / 'injector/build/libs/injector.jar'
EXE = ROOT / 'build/bootstrapper/UtilityClient-Setup.exe'
BASH = ROOT.parent / 'utilityclient-repair/toolchain/msys64/usr/bin/bash.exe'
cases = 0


def run(command, env=None, valid=True, timeout=90):
    result = subprocess.run([str(x) for x in command], cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    assert (result.returncode == 0) == valid, (command, result.returncode, result.stdout, result.stderr)
    return result.stdout + result.stderr


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def passed(name):
    global cases
    cases += 1
    print('PASS:', name, flush=True)


def check_jars(root):
    assert digest(root / 'cheatclient.jar') == digest(CLIENT)
    assert digest(root / 'injector.jar') == digest(INJECTOR)


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


helper = load('installer', ROOT / 'dist/install-artifacts.py')
bad = WORK / 'bad.zip'
with zipfile.ZipFile(bad, 'w') as archive:
    archive.writestr('dependency.jar', b'bad')
duplicates = WORK / 'duplicates.zip'
with zipfile.ZipFile(duplicates, 'w') as archive:
    archive.write(CLIENT, 'a/cheatclient.jar')
    archive.write(CLIENT, 'b/cheatclient.jar')
    archive.write(INJECTOR, 'injector.jar')
for artifact in [bad, duplicates]:
    run([sys.executable, ROOT / 'dist/install-artifacts.py', 'install', artifact, WORK / artifact.stem], valid=False)
    assert not (WORK / artifact.stem / 'cheatclient.jar').exists()
passed('wrong and duplicate artifacts rejected before installation')

special = WORK / 'spaces 雪 % dollar$'
special.mkdir()
helper.desktop(special, WORK / 'data')
entry = (WORK / 'data/applications/utilityclient.desktop').read_text()
assert '%%' in entry and '\\\\$' in entry and 'Terminal=false' in entry
passed('desktop entry quotes spaces, Unicode, percent and dollar signs')

# Force the second member to fail after replacing the first and check rollback.
rollback = WORK / 'rollback'
rollback.mkdir()
(rollback / 'cheatclient.jar').write_bytes(b'old client')
(rollback / 'injector.jar').write_bytes(b'old injector')
replace = helper.os.replace
def fail_injector(source, destination):
    if Path(destination).name == 'injector.jar':
        raise OSError('simulated locked injector')
    return replace(source, destination)
helper.os.replace = fail_injector
try:
    helper.install(BUNDLE, rollback)
    raise AssertionError('failed deployment accepted')
except OSError:
    assert (rollback / 'cheatclient.jar').read_bytes() == b'old client'
    assert (rollback / 'injector.jar').read_bytes() == b'old injector'
finally:
    helper.os.replace = replace
passed('file-set rollback preserves previous client and injector')

runtime_helper = load('runtime_setup', ROOT / 'dist/java-runtime.py')
runtime_helper.platform.system = lambda: 'Linux'
runtime_helper.platform.machine = lambda: 'x86_64'
def runtime_tar(name='jdk-25/bin/java'):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w:gz') as archive:
        member = tarfile.TarInfo(name)
        body = b'fake Java for extraction checks'
        member.size = len(body); member.mode = 0o755
        archive.addfile(member, io.BytesIO(body))
    return data.getvalue()
def provision_fake(data, checksum=None, folder='runtime mock'):
    meta = [{'binary': {'package': {'checksum': checksum or hashlib.sha256(data).hexdigest(), 'link': 'https://test.invalid/jdk.tar.gz'}}}]
    runtime_helper.fetch = lambda url: io.BytesIO(json.dumps(meta).encode() if 'api.adoptium' in url else data)
    return runtime_helper.provision(WORK / folder)
assert (provision_fake(runtime_tar()) / 'bin/java').is_file()
for data, checksum, folder in [(runtime_tar(), '0' * 64, 'runtime bad checksum'), (runtime_tar('../outside'), None, 'runtime bad path')]:
    try:
        provision_fake(data, checksum, folder)
        raise AssertionError('invalid runtime installed')
    except ValueError:
        assert not list((WORK / folder).glob('temurin-*'))
passed('private runtime verifies SHA-256 and rejects archive traversal (mocked download)')

if os.name == 'nt':
    install_root = WORK / 'Windows settings 雪 %'
    app = WORK / 'Windows runtime 雪 %'
    install_root.mkdir()
    (install_root / 'utilityclient.json').write_text('preserved config')
    shortcuts = WORK / 'test shortcuts'
    output = run([EXE, '--quiet', '--shortcut-dir', shortcuts, '--root', install_root, '--app-dir', app])
    check_jars(install_root)
    assert (install_root / 'utilityclient.json').read_text() == 'preserved config'
    assert '[6/6]' in output, output
    runtime = Path((install_root / 'java.path').read_text(encoding='utf-8'))
    assert 'jdk.attach@25' in run([runtime, '--list-modules'])
    assert 'UtilityClient injector' in run(['cmd', '/d', '/c', str(install_root / 'run.cmd'), '--help'])
    passed('offline Windows EXE installs runtime, preserves settings and launches with special paths')
    assert 'UtilityClient injector' in run([install_root / 'UtilityClient.exe', '--root', install_root, '--check'])
    script = WORK / 'read-shortcut.ps1'
    script.write_text("param([string]$Path,[string]$Report,[string]$Assembly)\n$a=[Reflection.Assembly]::LoadFile($Assembly)\n$t=$a.GetType('WindowsShortcuts')\n$m=$t.GetMethod('Read',[Reflection.BindingFlags]'Static,NonPublic')\n$values=$m.Invoke($null,@($Path))\n$json=@{Target=$values[0];Arguments=$values[1]}|ConvertTo-Json\n[IO.File]::WriteAllText($Report,$json,[Text.UTF8Encoding]::new($false))\n", encoding='utf-8-sig')
    shortcut_report = WORK / 'shortcut.json'
    run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, str(shortcuts / 'UtilityClient.lnk'), shortcut_report, EXE])
    shortcut = json.loads(shortcut_report.read_text(encoding='utf-8'))
    assert Path(shortcut['Target']).resolve() == (install_root / 'UtilityClient.exe').resolve(), shortcut
    assert '--root' in shortcut['Arguments']
    assert 'Startup check passed' in (install_root / 'launcher.log').read_text(encoding='utf-8')
    passed('saved shortcut targets the installed native launcher and startup is logged')
    broken_root = WORK / 'broken launcher install'; broken_root.mkdir()
    (broken_root / 'java.path').write_text(str(WORK / 'missing-java.exe'))
    output = run([install_root / 'UtilityClient.exe', '--root', broken_root, '--check'], valid=False)
    assert 'Private Java runtime is missing' in output
    assert 'STARTUP FAILED' in (broken_root / 'launcher.log').read_text(encoding='utf-8')
    passed('launcher reports missing runtime with an error and durable log')

    # A real Windows share lock on injector.jar must not leave an updated client behind.
    locked = WORK / 'locked settings'; locked.mkdir()
    (locked / 'cheatclient.jar').write_bytes(b'old client')
    (locked / 'injector.jar').write_bytes(b'old injector')
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
    kernel.CreateFileW.restype = ctypes.c_void_p
    handle = kernel.CreateFileW(str(locked / 'injector.jar'), 0x80000000, 0, None, 3, 0, None)
    assert handle not in (None, ctypes.c_void_p(-1).value)
    try:
        run([EXE, '--quiet', '--no-shortcuts', '--root', locked, '--app-dir', WORK / 'locked runtime'], valid=False)
    finally:
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        kernel.CloseHandle(handle)
    assert (locked / 'cheatclient.jar').read_bytes() == b'old client'
    assert (locked / 'injector.jar').read_bytes() == b'old injector'
    passed('native setup rolls back on an actual locked injector')

    corrupt = WORK / 'corrupt-setup.exe'
    payload = (ROOT / 'build/bootstrapper/Payload.zip').read_bytes()
    data = bytearray(EXE.read_bytes())
    position = data.find(payload)
    assert position >= 0, 'embedded payload not located'
    data[position + 100] ^= 1
    corrupt.write_bytes(data)
    output = run([corrupt, '--quiet', '--no-shortcuts', '--root', WORK / 'corrupt settings', '--app-dir', WORK / 'corrupt runtime'], valid=False)
    assert 'checksum' in output.lower()
    assert not (WORK / 'corrupt settings/injector.jar').exists()
    passed('corrupt embedded payload rejected before installing JARs')

if BASH.is_file():
    def bash(script, arguments, directory, valid=True):
        directory.mkdir(exist_ok=True)
        temporary = directory / 'tmp'; temporary.mkdir(exist_ok=True)
        env = os.environ.copy()
        # This Windows Bash runtime cannot walk protected ancestors with mkdir -p.
        # Relative fixture roots stay within run()'s project working directory.
        shell_root = directory.relative_to(ROOT) if os.name == 'nt' else directory
        env['XDG_CONFIG_HOME'] = shell_root.as_posix()
        env['XDG_DATA_HOME'] = (shell_root / 'data').as_posix()
        env['TMPDIR'] = (shell_root / 'tmp').as_posix()
        if os.name == 'nt':
            env['JAVA_HOME'] = runtime.parent.parent.as_posix()
        return run([BASH, '--noprofile', '--norc', '-c', 'export PATH=/usr/bin:$PATH; bash "$1" "${@:2}"', 'setup-check', script.as_posix(), *arguments], env=env, valid=valid)

    linux_dir = WORK / 'Linux spaces 雪'
    mods = WORK / 'Fabric instance/mods'; mods.mkdir(parents=True)
    output = bash(ROOT / 'dist/install.sh', ['--from', BUNDLE.as_posix(), '--offline', '--no-desktop', '--mods-dir', mods.as_posix()], linux_dir)
    check_jars(linux_dir / 'depends')
    assert digest(mods / 'cheatclient.jar') == digest(CLIENT)
    assert all('[' + str(n) + '/6]' in output for n in range(1, 7))
    launcher = linux_dir / 'depends/run.sh'
    launch_env = os.environ.copy()
    if os.name == 'nt':
        launch_env['JAVA_HOME'] = runtime.parent.parent.as_posix()
    assert 'UtilityClient injector' in run([BASH, '--noprofile', '--norc', '-c', 'export PATH=/usr/bin:$PATH; bash "$1" --help', 'launcher-check', launcher.as_posix()], env=launch_env)
    passed('Linux bundle installs with stages, chosen mods folder and working launcher')

    extracted = WORK / 'unpacked bundle'
    with zipfile.ZipFile(BUNDLE) as archive:
        archive.extractall(extracted)
    auto_dir = WORK / 'automatic'
    bash(extracted / 'install.sh', ['--offline', '--no-desktop'], auto_dir)
    check_jars(auto_dir / 'depends')
    passed('unpacked bundle installs without a --from argument')

    setup_dir = WORK / 'source bundle install'
    bash(ROOT / 'linux/setup.sh', ['--from', BUNDLE.as_posix(), '--offline', '--no-desktop'], setup_dir)
    check_jars(setup_dir / 'depends')
    passed('Linux source setup delegates bundle installation without rebuilding')
    for arguments in [['--from'], ['--wrong']]:
        bash(ROOT / 'linux/setup.sh', arguments, WORK / ('invalid-' + arguments[0][2:]), valid=False)
    passed('missing and unknown setup arguments fail before building')

    fake = WORK / 'old Java'; fake.mkdir()
    (fake / 'java').write_text('#!/usr/bin/env bash\necho \'openjdk version "21.0.1"\' >&2\n', newline='\n')
    missing_dir = WORK / 'missing Java'; missing_dir.mkdir()
    env = os.environ.copy()
    shell_missing = missing_dir.relative_to(ROOT) if os.name == 'nt' else missing_dir
    env['XDG_CONFIG_HOME'] = shell_missing.as_posix()
    env['XDG_DATA_HOME'] = (shell_missing / 'data').as_posix()
    command = [BASH, '--noprofile', '--norc', '-c',
               'export PATH="$(/usr/bin/cygpath -u "$3"):/usr/bin:$(/usr/bin/cygpath -u "$4")"; export JAVA_HOME="$3"; bash "$1" --from "$2" --offline --no-desktop',
               'missing-java-check', (ROOT / 'dist/install.sh').as_posix(), BUNDLE.as_posix(), fake.as_posix(), Path(sys.executable).parent.as_posix()]
    output = run(command, env=env, valid=False)
    assert 'Offline install needs Java 25' in output
    assert not (missing_dir / 'depends/injector.jar').exists()
    command[4] += ' --no-runtime'
    run(command, env=env)
    check_jars(missing_dir / 'depends')
    passed('offline missing runtime fails cleanly; --no-runtime installs files without downloading')

print('PASS:', cases, 'setup cases; isolated files:', WORK)
