"""Exercise the shipped setup with disposable roots; never touch a real installation."""
from pathlib import Path
import argparse
import ctypes
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

PROJECT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--setup', type=Path, default=PROJECT / 'build/bootstrapper/UtilityClient-Setup.exe')
parser.add_argument('--update', type=Path, default=PROJECT / 'build/bootstrapper/UtilityClient-Update.zip')
parser.add_argument('--test-java-home', type=Path, help='Fixture-only override for Windows sandboxes that deny security-file canonicalization')
args = parser.parse_args()
EXE, UPDATE = args.setup.resolve(), args.update.resolve()
WORK = Path(tempfile.mkdtemp(prefix='management-check-', dir=PROJECT / 'build'))
ROOT, APP, SHORTCUTS = WORK / 'profile 雪 % $', WORK / 'private runtime', WORK / 'shortcuts'
assert WORK.resolve().is_relative_to(PROJECT.resolve())
CASES = 0
TEST_ENV = os.environ.copy()
if args.test_java_home:
    assert (args.test_java_home / 'conf/security/java.security').is_file()
    TEST_ENV['JAVA_TOOL_OPTIONS'] = (TEST_ENV.get('JAVA_TOOL_OPTIONS', '') + ' -Djava.home="' + str(args.test_java_home.resolve()) + '"').strip()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(arguments, valid=True, timeout=120):
    command = [str(EXE), '--offline', '--root', str(ROOT), '--app-dir', str(APP), '--shortcut-dir', str(SHORTCUTS), *map(str, arguments)]
    result = subprocess.run(command, env=TEST_ENV, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    assert (result.returncode == 0) == valid, (command, result.returncode, result.stdout, result.stderr)
    return result.stdout + result.stderr


def passed(name):
    global CASES
    CASES += 1
    print('PASS:', name, flush=True)


def lock(path):
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [ctypes.c_wchar_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p]
    kernel.CreateFileW.restype = ctypes.c_void_p
    handle = kernel.CreateFileW(str(path), 0x80000000, 0, None, 3, 0, None)
    assert handle != ctypes.c_void_p(-1).value, ctypes.get_last_error()
    kernel.CloseHandle.argtypes = [ctypes.c_void_p]
    return kernel, handle


# Public-release discovery and path grammar are checked without external requests.
fixture_script = WORK / 'backend-checks.ps1'
fixture_script.write_text(r'''param([string]$Setup)
$ErrorActionPreference='Stop'
$assembly=[Reflection.Assembly]::LoadFile($Setup)
$type=$assembly.GetType('Bootstrapper')
$flags=[Reflection.BindingFlags]'Static,NonPublic'
$instance=[Reflection.BindingFlags]'Instance,NonPublic'
function Call([string]$Name,[object[]]$Values) {
    $actual=[object[]]::new($Values.Length)
    for ($i=0; $i -lt $Values.Length; $i++) { $actual[$i]=$Values[$i].PSObject.BaseObject }
    $type.GetMethod($Name,$flags).Invoke($null,$actual)
}
function Reject([string]$Name,[object[]]$Values) {
    try { $null=Call $Name $Values } catch { return }
    throw ('Accepted invalid input for '+$Name)
}
$hash='a'*64
$assets=@(@{name='UtilityClient-Update.zip';browser_download_url='https://github.com/o/r/releases/download/tag/UtilityClient-Update.zip';digest=('sha256:'+$hash)})
$json=@(@{tag_name='old';published_at='2026-01-01';draft=$false;prerelease=$false;assets=$assets},@{tag_name='draft';published_at='2026-12-01';draft=$true;prerelease=$false;assets=$assets},@{tag_name='pre';published_at='2026-12-02';draft=$false;prerelease=$true;assets=$assets},@{tag_name='new';published_at='2026-11-20';draft=$false;prerelease=$false;assets=$assets})|ConvertTo-Json -Depth 8 -Compress
$release=Call 'PickUpdate' @($json)
if ($release.GetType().GetField('Label',$instance).GetValue($release) -ne 'Release new') { throw 'Newest stable release selection failed' }
if ($release.GetType().GetField('Digest',$instance).GetValue($release) -ne $hash) { throw 'API asset digest lost' }
$missing=ConvertTo-Json -InputObject @(@{tag_name='unverified';published_at='2026-12-01';draft=$false;prerelease=$false;assets=@(@{name='UtilityClient-Update.zip';browser_download_url='https://github.com/o/r/pkg'})}) -Depth 8 -Compress
$release=Call 'PickUpdate' @($missing)
if ($release.GetType().GetProperty('Available',$instance).GetValue($release,$null)) { throw 'Unverified online package accepted' }
$checksum=Call 'ParseChecksum' @(($hash+'  UtilityClient-Update.zip' + "`n"),'UtilityClient-Update.zip')
if ($checksum -ne $hash) { throw 'Checksum parse failed' }
Reject 'ParseChecksum' @(($hash+'  other.zip'),'UtilityClient-Update.zip')
Reject 'ParseChecksum' @(($hash+'  UtilityClient-Update.zip'+"`n"+$hash+'  UtilityClient-Update.zip'),'UtilityClient-Update.zip')
foreach ($name in @('../outside','/outside','C:/outside','aad','con','a/../b','a.','a ')) { Reject 'SafeMember' @([IO.Path]::GetTempPath(),$name) }
$schema=Call 'ObjectJson' @('{"schema":1.5}')
Reject 'RequireSchema' @($schema)
Write-Output 'PASS: stable release ranking, asset digest, absent digest rejection, checksum matching/duplicates, unsafe paths and schema types'
''', encoding='utf-8-sig')
result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(fixture_script), str(EXE)], capture_output=True, text=True, timeout=30)
assert result.returncode == 0, result.stdout + result.stderr
passed('update discovery and validation use isolated release fixtures')

ROOT.mkdir(); (ROOT / 'utilityclient.json').write_text('saved settings')
profiles = ROOT / 'utilityclient/presets'; profiles.mkdir(parents=True); (profiles / 'mine.json').write_text('saved profile')
(ROOT / 'unrelated.txt').write_text('retain unrelated data')
assert '[6/6]' in run(['--quiet'])
record = json.loads((ROOT / 'installation.json').read_text())
assert set(record['managedFiles']) == {'cheatclient.jar', 'injector.jar', 'UtilityClient.exe', 'run.cmd', 'java.path'}
assert (ROOT / 'utilityclient.json').read_text() == 'saved settings'
assert sha(APP / 'bootstrapper/UtilityClient-Setup.exe') == sha(EXE)
assert 'Installation healthy' in run(['--health'])
passed('installation records, runtime/helper integrity, shortcuts and retained setup')

exported = WORK / 'UtilityClient-Linux.zip'
run(['--export-linux', exported])
assert exported.with_name(exported.name + '.sha256').read_text().split()[0] == sha(exported)
with zipfile.ZipFile(exported) as archive:
    assert archive.testzip() is None
    assert not any(name.lower().endswith(('.exe', '.dll')) for name in archive.namelist())
    import io
    with zipfile.ZipFile(io.BytesIO(archive.read('cheatclient.jar'))) as client:
        assert 'assets/x/r.bin' not in client.namelist()
passed('Linux export checks exact ZIP, all member hashes and Linux native resources')
run(['--export-linux', ROOT / 'overwrite.zip'], valid=False)
run(['--export-linux', WORK / 'wrong.exe'], valid=False)
passed('export rejects managed-folder targets and wrong extensions')

old_export = exported.read_bytes(); old_checksum = exported.with_name(exported.name + '.sha256').read_bytes()
kernel, handle = lock(exported.with_name(exported.name + '.sha256'))
try:
    run(['--export-linux', exported], valid=False)
    assert exported.read_bytes() == old_export
finally:
    kernel.CloseHandle(handle)
assert exported.with_name(exported.name + '.sha256').read_bytes() == old_checksum
passed('Linux ZIP/checksum pair rolls back when sidecar is locked')

(ROOT / 'run.cmd').write_text('corrupted launcher')
assert 'Recorded checksum differs' in run(['--health'], valid=False)
run(['--quiet']); assert 'Installation healthy' in run(['--health'])
passed('health detects generated-launcher corruption and repair restores it')

original = (ROOT / 'injector.jar').read_bytes()
(ROOT / 'injector.jar').write_bytes(b'broken jar')
output = run(['--health'], valid=False)
assert 'Repair the injector.jar checksum before running verification' in output
(ROOT / 'injector.jar').write_bytes(original)
passed('health refuses to execute an injector with a mismatched recorded hash')

before = {name: (ROOT / name).read_bytes() for name in record['managedFiles']}
kernel, handle = lock(ROOT / 'injector.jar')
try:
    run(['--uninstall', '--confirm'], valid=False)
finally:
    kernel.CloseHandle(handle)
assert all((ROOT / name).read_bytes() == data for name, data in before.items())
passed('locked uninstall leaves the complete installation intact')

registration = next((APP / 'installations').glob('*.root'))
kernel, handle = lock(registration)
try:
    run(['--uninstall', '--confirm'], valid=False)
finally:
    kernel.CloseHandle(handle)
assert all((ROOT / name).read_bytes() == data for name, data in before.items())
assert (ROOT / 'installation.json').exists() and (SHORTCUTS / 'UtilityClient.lnk').exists()
passed('late registration failure rolls back program files and owned shortcuts')

update_hash = sha(UPDATE)
run(['--quiet', '--update-package', UPDATE, '--package-sha256', update_hash])
assert sha(APP / 'bootstrapper/UtilityClient-Update.zip') == update_hash
assert (ROOT / 'utilityclient.json').read_text() == 'saved settings'
assert 'Installation healthy' in run(['--health'])
run(['--quiet'])
assert 'Cached verified update' in (ROOT / 'installation.json').read_text()
passed('same bootstrapper installs a checked update and retains it for offline repair')

version_script = WORK / 'version-checks.ps1'
version_script.write_text(r'''param([string]$Setup,[string]$Root,[string]$App,[string]$Digest)
$assembly=[Reflection.Assembly]::LoadFile($Setup)
$type=$assembly.GetType('Bootstrapper')
$flags=[Reflection.BindingFlags]'Static,NonPublic'
$instance=[Reflection.BindingFlags]'Instance,NonPublic'
$type.GetField('Root',$flags).SetValue($null,$Root)
$type.GetField('AppRoot',$flags).SetValue($null,$App)
$release=[Activator]::CreateInstance($assembly.GetType('Bootstrapper+UpdateRelease'),$true)
$release.GetType().GetField('Url',$instance).SetValue($release,'https://github.com/test/update.zip')
$release.GetType().GetField('Digest',$instance).SetValue($release,$Digest.ToUpperInvariant())
$check=$type.GetMethod('IsReleaseInstalled',$flags)
1..3 | ForEach-Object { if (-not $check.Invoke($null,@($release))) { throw 'Repeated check failed' } }
$recordPath=Join-Path $Root 'installation.json'
$original=[IO.File]::ReadAllText($recordPath)
try {
    $record=$original | ConvertFrom-Json
    $record.PSObject.Properties.Remove('updateSha256')
    [IO.File]::WriteAllText($recordPath,($record | ConvertTo-Json -Depth 10))
    $type.GetMethod('RestoreSelectedUpdate',$flags).Invoke($null,@())
    if (-not $check.Invoke($null,@($release))) { throw 'Legacy record detection failed' }
    $record.payloadSha256='0'*64
    [IO.File]::WriteAllText($recordPath,($record | ConvertTo-Json -Depth 10))
    if ($check.Invoke($null,@($release))) { throw 'Cached package treated as installed' }
} finally { [IO.File]::WriteAllText($recordPath,$original); $type.GetMethod('CleanupUpdate',$flags).Invoke($null,@()) }
$release.GetType().GetField('Digest',$instance).SetValue($release,'1'*64)
if ($check.Invoke($null,@($release))) { throw 'Different release treated as installed' }
$release.GetType().GetField('Digest',$instance).SetValue($release,$Digest)
$missing=Join-Path $Root 'installation.json.test-backup'
[IO.File]::Move($recordPath,$missing)
try { if ($check.Invoke($null,@($release))) { throw 'Missing installation treated as installed' } }
finally { [IO.File]::Move($missing,$recordPath) }
Write-Output 'PASS: repeated checks, case-insensitive identity, legacy records, cached-only, changed release and absent installation'
''', encoding='utf-8-sig')
result = subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(version_script), str(EXE), str(ROOT), str(APP), update_hash], capture_output=True, text=True, timeout=30)
assert result.returncode == 0, result.stdout + result.stderr
passed('installed version detection survives repeated checks and legacy records')

run(['--quiet', '--update-package', UPDATE, '--package-sha256', '0' * 64], valid=False)
assert all((ROOT / name).read_bytes() == data for name, data in before.items())
bad_update = WORK / 'bad-update.zip'
with zipfile.ZipFile(UPDATE) as source, zipfile.ZipFile(bad_update, 'w', zipfile.ZIP_STORED) as target:
    for item in source.infolist():
        data = source.read(item)
        if item.filename == 'Payload.zip': data = bytes([data[0] ^ 1]) + data[1:]
        target.writestr(item.filename, data)
run(['--quiet', '--update-package', bad_update], valid=False)
assert (ROOT / 'installation.json').exists() and sha(ROOT / 'injector.jar') == hashlib.sha256(original).hexdigest()
passed('wrong outer digest and damaged update member are rejected before install')

run(['--uninstall'], valid=False)
assert (ROOT / 'installation.json').exists()
run(['--uninstall', '--confirm'])
assert not (ROOT / 'injector.jar').exists() and not (SHORTCUTS / 'UtilityClient.lnk').exists()
assert (ROOT / 'utilityclient.json').read_text() == 'saved settings' and (profiles / 'mine.json').exists()
assert (ROOT / 'unrelated.txt').exists() and (APP / 'bootstrapper/UtilityClient-Setup.exe').exists()
passed('uninstall confirmation required; default keeps settings/profiles/unrelated files/setup')

run(['--quiet']); (ROOT / 'friends.json').write_text('saved friends')
(ROOT / 'logs').mkdir(exist_ok=True); (ROOT / 'logs/client.log').write_text('managed log')
run(['--uninstall', '--confirm', '--remove-settings'])
assert not (ROOT / 'utilityclient.json').exists() and not (ROOT / 'friends.json').exists() and not profiles.exists() and not (ROOT / 'logs').exists()
assert (ROOT / 'unrelated.txt').exists() and (APP / 'bootstrapper/UtilityClient-Setup.exe').exists()
passed('explicit settings removal keeps unrelated data and reusable setup')
assert not list(ROOT.glob('.uninstall-*'))
print(json.dumps({'cases': CASES, 'result': 'PASS', 'disposable_workspace': str(WORK), 'test_java_home': str(args.test_java_home) if args.test_java_home else None}), flush=True)
