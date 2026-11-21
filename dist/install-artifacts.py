#!/usr/bin/env python3
"""Validate a distribution before replacing any installed JAR; preserve user configuration."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile


def checksums(text):
    entries = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        if len(line) < 66 or any(c not in '0123456789abcdefABCDEF' for c in line[:64]):
            raise ValueError('Invalid SHA256SUMS line')
        name = line[64:].strip().lstrip('*')
        if not name or '\\' in name or ':' in name or name.startswith('/') or any(part in ('', '.', '..') for part in name.split('/')):
            raise ValueError('Invalid checksum member path')
        if name == 'SHA256SUMS' or name in entries:
            raise ValueError('Duplicate checksum member')
        entries[name] = line[:64].lower()
    return entries


def verify_bundle(artifact):
    if artifact.is_dir():
        manifest = artifact / 'SHA256SUMS'
        if not manifest.is_file():
            raise ValueError('Bundle SHA256SUMS is missing; use a complete verified release')
        expected = checksums(manifest.read_text(encoding='utf-8'))
        for name, digest in expected.items():
            path = artifact / name
            if not path.is_file() or path.is_symlink() or path.stat().st_size > 512 * 1024 * 1024:
                raise ValueError('Missing or invalid bundle member: ' + name)
            if os.path.commonpath([str(path.resolve()), str(artifact.resolve())]) != str(artifact.resolve()):
                raise ValueError('Bundle member escapes its root')
            with path.open('rb') as stream:
                sha = hashlib.sha256()
                for chunk in iter(lambda: stream.read(65536), b''):
                    sha.update(chunk)
            if sha.hexdigest() != digest:
                raise ValueError('Bundle checksum mismatch: ' + name)
        for required in ('cheatclient.jar', 'injector.jar', 'install.sh', 'install-artifacts.py', 'java-runtime.py', 'README.md'):
            if required not in expected:
                raise ValueError('Bundle checksum missing: ' + required)
    else:
        with zipfile.ZipFile(artifact) as archive:
            if 'fabric.mod.json' in archive.namelist():
                return
            entries = archive.infolist()
            if len(entries) > 25000 or sum(item.file_size for item in entries) > 512 * 1024 * 1024:
                raise ValueError('Bundle expansion limit exceeded')
            if len(set(item.filename for item in entries)) != len(entries):
                raise ValueError('Duplicate bundle member')
            expected = checksums(archive.read('SHA256SUMS').decode('utf-8'))
            present = {item.filename for item in entries if not item.is_dir() and item.filename != 'SHA256SUMS'}
            if set(expected) != present:
                raise ValueError('Bundle checksum members do not match archive')
            for name, digest in expected.items():
                if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                    raise ValueError('Bundle checksum mismatch: ' + name)


def validate(data, injector=False):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        entries = jar.infolist()
        if len(entries) > 60000 or len(set(item.filename for item in entries)) != len(entries) or sum(item.file_size for item in entries) > 512 * 1024 * 1024:
            raise ValueError('Invalid or oversized JAR archive')
        if jar.testzip() is not None:
            raise ValueError('Damaged JAR entry')
        if injector:
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '').replace('\n ', '')
            fields = dict(line.split(': ', 1) for line in manifest.splitlines() if ': ' in line)
            if fields.get('Main-Class') != 'com.baggute.cheatclient.injector.InjectorMain' or fields.get('Agent-Class') != 'com.baggute.cheatclient.injector.agent.ClientAgent':
                raise ValueError('Invalid injector identity')
            if 'com/baggute/cheatclient/injector/InjectorMain.class' not in jar.namelist():
                raise ValueError('Injector entrypoint missing')
        else:
            if json.loads(jar.read('fabric.mod.json'))['id'] not in ('utilityclient', 'cheatclient'):
                raise ValueError('Wrong client mod identity')
            for helper in ('UtilityClient', 'LateBridge', 'GameplayHooks', 'ContainerMouseHooks'):
                if jar.read('com/baggute/cheatclient/' + helper + '.class')[:4] != b'\xca\xfe\xba\xbe':
                    raise ValueError('Client helper bytecode missing or invalid: ' + helper)


def read_artifact(artifact):
    verify_bundle(artifact)
    if artifact.is_dir():
        jars = {name: (artifact / name).read_bytes() for name in ('cheatclient.jar', 'injector.jar')}
    else:
        with zipfile.ZipFile(artifact) as bundle:
            if 'fabric.mod.json' in bundle.namelist():
                jars = {'cheatclient.jar': artifact.read_bytes()}
            else:
                jars = {}
                for name in ('cheatclient.jar', 'injector.jar'):
                    entries = [entry for entry in bundle.infolist() if not entry.is_dir() and entry.filename.rsplit('/', 1)[-1] == name]
                    if len(entries) != 1:
                        raise ValueError('Bundle requires exactly one ' + name)
                    if entries[0].file_size > 256 * 1024 * 1024:
                        raise ValueError('JAR exceeds 256 MiB limit')
                    jars[name] = bundle.read(entries[0])
    for name, data in jars.items():
        validate(data, name == 'injector.jar')
    return jars


def install(artifact, root, mods=None, launcher=None, java=None):
    root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.install-', dir=root) as directory:
        stage = Path(directory)
        jars = read_artifact(artifact)
        for name, data in jars.items():
            validate(data, name == 'injector.jar')
            (stage / name).write_bytes(data)
        destinations = [(stage / name, root / name) for name in jars]
        if launcher is not None:
            if 'injector.jar' not in jars:
                raise ValueError('A launcher requires an injector bundle')
            shutil.copy2(launcher, stage / 'run.sh')
            destinations.append((stage / 'run.sh', root / 'run.sh'))
        if java is not None:
            if any(char in java for char in '\r\n'):
                raise ValueError('Java paths cannot contain line breaks')
            (stage / 'runtime.path').write_text(java + '\n', encoding='utf-8')
            destinations.append((stage / 'runtime.path', root / 'runtime.path'))
        if mods is not None:
            if not mods.is_dir():
                raise ValueError('--mods-dir must name an existing Fabric instance mods folder')
            if (mods / 'cheatclient.jar').resolve() != (root / 'cheatclient.jar').resolve():
                destinations.append((stage / 'cheatclient.jar', mods / 'cheatclient.jar'))
        changed = []
        try:
            for index, (source, destination) in enumerate(destinations):
                original = stage / ('backup-' + str(index))
                existed = destination.exists()
                if existed:
                    shutil.copy2(destination, original)
                # Stage on the destination filesystem so the rename is atomic.
                descriptor, pending = tempfile.mkstemp(prefix='.utilityclient-', dir=destination.parent)
                os.close(descriptor)
                try:
                    shutil.copy2(source, pending)
                    os.replace(pending, destination)
                finally:
                    if os.path.exists(pending):
                        os.unlink(pending)
                changed.append((destination, original, existed))
        except Exception as install_error:
            try:
                for destination, original, existed in reversed(changed):
                    if existed:
                        shutil.copy2(original, destination)
                    else:
                        destination.unlink()
            except Exception as rollback_error:
                import uuid
                recovery = root / ('.recovery-' + uuid.uuid4().hex)
                stage.rename(recovery)
                raise OSError(str(install_error) + '; rollback incomplete; backups retained at ' + str(recovery)) from rollback_error
            raise
        return (root / 'injector.jar').is_file()


def desktop(root, data):
    launcher = str((root / 'run.sh').resolve())
    if any(char in launcher for char in '\r\n'):
        raise ValueError('Desktop launcher paths cannot contain line breaks')
    # Desktop Exec quoting has two escaping layers (key value and argument).
    escaped = launcher.replace('\\', '\\\\\\\\').replace('"', '\\\\"').replace('`', '\\\\`').replace('$', '\\\\$')
    escaped = escaped.replace('%', '%%')
    target = data / 'applications' / 'utilityclient.desktop'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text('[Desktop Entry]\nType=Application\nName=UtilityClient\nComment=Open the UtilityClient injector\nExec="' + escaped + '"\nIcon=applications-games\nTerminal=false\nCategories=Game;Utility;\n', encoding='utf-8')
    target.chmod(0o644)
    print('Application menu entry: ' + str(target))


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('validate')
    check.add_argument('artifact', type=Path)
    deploy = sub.add_parser('install')
    deploy.add_argument('artifact', type=Path)
    deploy.add_argument('root', type=Path)
    deploy.add_argument('--mods-dir', type=Path)
    deploy.add_argument('--launcher', type=Path)
    deploy.add_argument('--java')
    menu = sub.add_parser('desktop')
    menu.add_argument('root', type=Path)
    menu.add_argument('data', type=Path)
    args = parser.parse_args()
    if args.command == 'validate':
        print('injector=yes' if 'injector.jar' in read_artifact(args.artifact) else 'injector=no')
    elif args.command == 'install':
        args.root.mkdir(parents=True, exist_ok=True)
        with (args.root / '.setup.lock').open('a+b') as lock:
            if os.name != 'nt':
                import fcntl
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            print('injector=yes' if install(args.artifact, args.root, args.mods_dir, args.launcher, args.java) else 'injector=no')
    else:
        desktop(args.root, args.data)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit('Installation failed: ' + str(error))
