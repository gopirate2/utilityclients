"""Publish a complete updater-compatible GitHub release after a successful build."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def assets(directory):
    names = ('UtilityClient-Setup-Updated.exe', 'UtilityClient-Update.zip', 'UtilityClient-Update.zip.sha256')
    result = {name: (directory / name).read_bytes() for name in names}
    expected = result['UtilityClient-Update.zip.sha256'].decode('ascii').split()[0]
    if expected != digest(result['UtilityClient-Update.zip']):
        raise ValueError('Update checksum mismatch; release was not submitted')
    with zipfile.ZipFile(directory / 'UtilityClient-Update.zip') as archive:
        if archive.testzip() is not None:
            raise ValueError('Corrupt update ZIP')
        metadata = json.loads(archive.read('update.json'))
        if metadata['schema'] != 1:
            raise ValueError('Unsupported update schema')
        for name in ('Payload.zip', 'LinuxBundle.zip'):
            if digest(archive.read(name)) != metadata['files'][name]:
                raise ValueError('Corrupt update member: ' + name)
    identity = hashlib.sha256()
    for name, data in sorted(result.items()):
        identity.update(name.encode()); identity.update(hashlib.sha256(data).digest())
    return result, metadata, 'client-' + identity.hexdigest()[:20]


def api(method, url, payload=None, content_type='application/json'):
    token = os.environ.get('GH_TOKEN') or os.environ.get('GITHUB_TOKEN')
    if not token:
        raise ValueError('Authenticate once using GH_TOKEN or GITHUB_TOKEN; no token is stored in the installer')
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in ('api.github.com', 'uploads.github.com'):
        raise ValueError('Unexpected GitHub API endpoint')
    if payload is not None and not isinstance(payload, bytes):
        payload = json.dumps(payload).encode()
    request = urllib.request.Request(url, data=payload, method=method, headers={
        'Authorization': 'Bearer ' + token, 'Accept': 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': content_type,
        'User-Agent': 'UtilityClient-ReleasePublisher'})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            data = response.read()
            return json.loads(data) if data else {}
    except urllib.error.HTTPError as error:
        if method == 'GET' and error.code == 404:
            return None
        raise RuntimeError('GitHub API returned HTTP ' + str(error.code) + '; the release remains unpublished if upload failed') from None


def publish(repository, files, metadata, tag, caller=api):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Use an owner/repository name')
    base = 'https://api.github.com/repos/' + repository + '/releases'
    release = caller('GET', base + '/tags/' + tag)
    if release and not release['draft']:
        available = {asset['name']: asset['size'] for asset in release['assets']}
        if all(available.get(name) == len(data) for name, data in files.items()):
            return {'status': 'already published', 'url': release['html_url']}
        raise ValueError('Existing published release is incomplete; it was preserved')
    if release is None:
        notes = 'Automatically built client and installer. The setup checks for future verified update packages and preserves saved settings.'
        if re.fullmatch(r'\d+\.\d+\.\d+', metadata['version']):
            changelog = Path(__file__).resolve().parents[1] / ('RELEASE-' + metadata['version'] + '.md')
            if changelog.is_file():
                notes = changelog.read_text(encoding='utf-8')
        release = caller('POST', base, {'tag_name': tag, 'name': 'UtilityClient ' + metadata['version'] + ' — ' + metadata['build'],
            'draft': True, 'prerelease': False,
            'body': notes})
    existing = {asset['name']: asset for asset in release.get('assets', [])}
    upload = release['upload_url'].split('{', 1)[0]
    for name, data in files.items():
        if name in existing:
            caller('DELETE', 'https://api.github.com/repos/' + repository + '/releases/assets/' + str(existing[name]['id']))
        asset = caller('POST', upload + '?name=' + urllib.parse.quote(name, safe=''), data, 'application/octet-stream')
        if asset.get('size') != len(data) or asset.get('state') != 'uploaded':
            raise RuntimeError('Upload was not confirmed; release remains a draft')
        if asset.get('digest') and asset['digest'] != 'sha256:' + digest(data):
            raise RuntimeError('Uploaded asset checksum mismatch; release remains a draft')
    completed = caller('PATCH', base + '/' + str(release['id']), {'draft': False, 'prerelease': False, 'make_latest': 'true'})
    return {'status': 'published', 'url': completed['html_url']}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--repository', default='gopirate2/utilityclients')
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    files, metadata, tag = assets(args.directory)
    if args.publish:
        print(json.dumps(publish(args.repository, files, metadata, tag)))
    else:
        print(json.dumps({'status': 'ready', 'repository': args.repository, 'tag': tag,
                          'assets': {name: {'bytes': len(data), 'sha256': digest(data)} for name, data in files.items()}}, indent=2))


if __name__ == '__main__':
    main()
