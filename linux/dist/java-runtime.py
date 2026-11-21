#!/usr/bin/env python3
"""Provision a private Linux Temurin JDK 25 without sudo or system configuration changes."""
import hashlib
import json
import os
from pathlib import Path
import platform
import posixpath
import shutil
import sys
import tarfile
import tempfile
import urllib.request


def fetch(url):
    if not url.startswith('https://'):
        raise ValueError('Runtime URL must use HTTPS')
    response = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'UtilityClient-Setup/1.0'}), timeout=60)
    if not response.url.startswith('https://'):
        response.close()
        raise ValueError('Runtime redirect must use HTTPS')
    return response


def provision(base):
    if platform.system() != 'Linux':
        raise ValueError('Automatic runtime download supports Linux; set JAVA_HOME to a JDK 25 on this platform')
    arch = {'x86_64': 'x64', 'amd64': 'x64', 'aarch64': 'aarch64', 'arm64': 'aarch64'}.get(platform.machine().lower())
    if not arch:
        raise ValueError('Unsupported architecture; set JAVA_HOME to a matching JDK 25')
    endpoint = 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=' + arch + '&image_type=jdk&os=linux&vendor=eclipse'
    with fetch(endpoint) as response:
        assets = json.load(response)
    if not assets:
        raise ValueError('No matching JDK 25 runtime available')
    package = assets[0]['binary']['package']
    expected = package['checksum'].lower()
    if len(expected) != 64 or any(c not in '0123456789abcdef' for c in expected):
        raise ValueError('Invalid runtime checksum metadata')
    base.mkdir(parents=True, exist_ok=True)
    installed = base / ('temurin-25-' + expected[:16])
    if (installed / 'bin/java').is_file():
        return installed
    with tempfile.TemporaryDirectory(prefix='.runtime-', dir=base) as directory:
        work = Path(directory)
        archive = work / 'jdk.tar.gz'
        digest = hashlib.sha256()
        with fetch(package['link']) as response, archive.open('wb') as output:
            total = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > 512 * 1024 * 1024:
                    raise ValueError('Runtime download exceeds size limit')
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != expected:
            raise ValueError('Runtime checksum mismatch; no files installed')
        unpacked = work / 'unpacked'
        unpacked.mkdir()
        with tarfile.open(archive) as tar:
            members = tar.getmembers()
            total = 0
            for member in members:
                name = posixpath.normpath(member.name)
                if name.startswith('/') or name == '..' or name.startswith('../') or '\\' in name:
                    raise ValueError('Invalid runtime archive path')
                if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                    raise ValueError('Unsupported runtime archive entry')
                if member.issym() or member.islnk():
                    target = posixpath.normpath(posixpath.join(posixpath.dirname(name), member.linkname) if member.issym() else member.linkname)
                    if target.startswith('/') or target == '..' or target.startswith('../'):
                        raise ValueError('Runtime archive link escapes installation')
                total += member.size
                if total > 1024 * 1024 * 1024:
                    raise ValueError('Unpacked runtime exceeds size limit')
            # Reject symlink traversal even if a subsequent member points through a link.
            links = {posixpath.normpath(m.name) for m in members if m.issym() or m.islnk()}
            for member in members:
                parents = Path(posixpath.normpath(member.name)).parents
                if any(str(parent).replace('\\', '/') in links for parent in parents):
                    raise ValueError('Runtime archive writes through a link')
            for member in members:
                tar.extract(member, unpacked, set_attrs=True)
        homes = [p.parent.parent for p in unpacked.glob('*/bin/java') if p.is_file()]
        if len(homes) != 1:
            raise ValueError('Runtime archive has no unique Java home')
        try:
            homes[0].rename(installed)
        except FileExistsError:
            if not (installed / 'bin/java').is_file():
                raise
        return installed


if __name__ == '__main__':
    try:
        print(provision(Path(sys.argv[1])))
    except Exception as error:
        raise SystemExit('Java setup failed: ' + str(error))
