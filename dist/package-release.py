#!/usr/bin/env python3
"""Create content manifests and one reusable-bootstrapper update package."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import zipfile


def digest(path):
    sha = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            sha.update(chunk)
    return sha.hexdigest()


def atomic_text(path, text):
    pending = path.with_name(path.name + '.new')
    try:
        pending.write_text(text, encoding='utf-8')
        os.replace(pending, path)
    finally:
        pending.unlink(missing_ok=True)


def manifest(directory, version, build):
    files = {path.relative_to(directory).as_posix(): digest(path) for path in sorted(directory.rglob('*'))
             if path.is_file() and path.name not in ('package.json', 'package.json.new')}
    atomic_text(directory / 'package.json', json.dumps({'schema': 1, 'version': version, 'build': build, 'files': files}, indent=2) + '\n')


def sums(directory):
    files = {path.relative_to(directory).as_posix(): digest(path) for path in sorted(directory.rglob('*'))
             if path.is_file() and path.name not in ('SHA256SUMS', 'SHA256SUMS.new')}
    atomic_text(directory / 'SHA256SUMS', ''.join(sha + '  ' + name + '\n' for name, sha in files.items()))


def verify_linux(bundle):
    with zipfile.ZipFile(bundle) as archive:
        assert archive.testzip() is None, 'Linux bundle CRC failure'
        all_members = archive.namelist()
        assert len(all_members) == len(set(all_members)), 'Duplicate Linux bundle member'
        members = [item.filename for item in archive.infolist() if not item.is_dir()]
        required = {'cheatclient.jar', 'injector.jar', 'install.sh', 'install-artifacts.py', 'java-runtime.py', 'README.md', 'SHA256SUMS'}
        assert required.issubset(members), 'Linux bundle is incomplete'
        assert not any(name.lower().endswith(('.exe', '.dll')) for name in members), 'Linux bundle contains Windows binaries'
        import io
        with zipfile.ZipFile(io.BytesIO(archive.read('cheatclient.jar'))) as client:
            assert 'assets/x/r.bin' not in client.namelist(), 'Linux client contains the Windows native DLL'
        expected = {}
        for line in archive.read('SHA256SUMS').decode('utf-8').splitlines():
            sha, name = line.split('  ', 1)
            assert name not in expected and len(sha) == 64
            expected[name] = sha
        assert set(expected) == set(members) - {'SHA256SUMS'}, 'Linux checksum member mismatch'
        assert all(hashlib.sha256(archive.read(name)).hexdigest() == sha for name, sha in expected.items()), 'Linux member checksum mismatch'


def update(payload, linux, output, version, build):
    verify_linux(linux)
    output.mkdir(parents=True, exist_ok=True)
    destination = output / 'UtilityClient-Update.zip'
    temporary = destination.with_name(destination.name + '.new')
    metadata = {'schema': 1, 'repository': 'gopirate2/utilityclients', 'version': version, 'build': build,
                'files': {'Payload.zip': digest(payload), 'LinuxBundle.zip': digest(linux)}}
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_STORED) as archive:
            archive.write(payload, 'Payload.zip'); archive.write(linux, 'LinuxBundle.zip')
            archive.writestr('update.json', json.dumps(metadata, indent=2) + '\n')
        with zipfile.ZipFile(temporary) as archive:
            assert archive.testzip() is None, 'Update package CRC failure'
        os.replace(temporary, destination)
        atomic_text(destination.with_name(destination.name + '.sha256'), digest(destination) + '  ' + destination.name + '\n')
        atomic_text(output / 'update.json', json.dumps(metadata, indent=2) + '\n')
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('manifest'); command.add_argument('directory', type=Path); command.add_argument('--version', default='1.0.0'); command.add_argument('--build', required=True)
    command = commands.add_parser('sums'); command.add_argument('directory', type=Path)
    command = commands.add_parser('update'); command.add_argument('--payload', type=Path, required=True); command.add_argument('--linux', type=Path, required=True)
    command.add_argument('--output', type=Path, required=True); command.add_argument('--version', default='1.0.0'); command.add_argument('--build', required=True)
    args = parser.parse_args()
    if args.command == 'manifest': manifest(args.directory, args.version, args.build)
    elif args.command == 'sums': sums(args.directory)
    else: update(args.payload, args.linux, args.output, args.version, args.build)
