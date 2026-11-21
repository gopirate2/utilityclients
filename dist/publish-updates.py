"""Prepare or publish an immutable package and an atomic stable feed to R2/S3."""
import argparse
import datetime
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request
import zipfile
from update_server_config import settings


def https_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('Use a public HTTPS base URL without credentials, query or fragment')
    return value.rstrip('/')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def prepare(package, public_base, output):
    public_base = https_url(public_base)
    data = package.read_bytes()
    if not 0 < len(data) <= 512 * 1024 * 1024:
        raise ValueError('Update archive exceeds the bootstrapper size limit')
    with zipfile.ZipFile(package) as archive:
        if archive.testzip() is not None:
            raise ValueError('Corrupt update archive')
        metadata = json.loads(archive.read('update.json'))
        if metadata['schema'] != 1:
            raise ValueError('Unsupported package schema')
        for name in ('Payload.zip', 'LinuxBundle.zip'):
            if sha(archive.read(name)) != metadata['files'][name]:
                raise ValueError('Update member checksum mismatch: ' + name)
    digest = sha(data)
    key = 'packages/' + digest + '/UtilityClient-Update.zip'
    feed = {'schema': 1, 'channel': 'stable', 'version': metadata['version'], 'build': metadata['build'],
            'published': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'package': {'url': public_base + '/' + key, 'sha256': digest, 'bytes': len(data)}}
    output.mkdir(parents=True, exist_ok=True)
    (output / 'update-feed.json').write_text(json.dumps(feed, indent=2) + '\n', encoding='utf-8')
    (output / 'UpdateFeed.url').write_text(public_base + '/update.json\n', encoding='utf-8')
    return key, feed


def put(endpoint, bucket, key, data, content_type, cache_control):
    access = os.environ.get('AWS_ACCESS_KEY_ID', '')
    secret = os.environ.get('AWS_SECRET_ACCESS_KEY', '')
    if not access or not secret:
        raise ValueError('Set AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY for the update bucket')
    endpoint = https_url(endpoint)
    if not re.fullmatch(r'[A-Za-z0-9._-]+', bucket):
        raise ValueError('Invalid bucket name')
    url = endpoint + '/' + urllib.parse.quote(bucket, safe='') + '/' + urllib.parse.quote(key, safe='/')
    parsed = urllib.parse.urlsplit(url)
    now = datetime.datetime.now(datetime.timezone.utc)
    date, timestamp = now.strftime('%Y%m%d'), now.strftime('%Y%m%dT%H%M%SZ')
    headers = {'host': parsed.netloc, 'x-amz-content-sha256': sha(data), 'x-amz-date': timestamp,
               'content-type': content_type, 'cache-control': cache_control}
    if os.environ.get('AWS_SESSION_TOKEN'):
        headers['x-amz-security-token'] = os.environ['AWS_SESSION_TOKEN']
    signed = ';'.join(sorted(headers))
    canonical_headers = ''.join(name + ':' + headers[name] + '\n' for name in sorted(headers))
    canonical = '\n'.join(('PUT', parsed.path, '', canonical_headers, signed, sha(data)))
    region = os.environ.get('AWS_REGION', 'auto')
    scope = date + '/' + region + '/s3/aws4_request'
    to_sign = 'AWS4-HMAC-SHA256\n' + timestamp + '\n' + scope + '\n' + sha(canonical.encode())
    signing = ('AWS4' + secret).encode()
    for value in (date, region, 's3', 'aws4_request'):
        signing = hmac.new(signing, value.encode(), hashlib.sha256).digest()
    signature = hmac.new(signing, to_sign.encode(), hashlib.sha256).hexdigest()
    headers['Authorization'] = 'AWS4-HMAC-SHA256 Credential=' + access + '/' + scope + ', SignedHeaders=' + signed + ', Signature=' + signature
    request = urllib.request.Request(url, data=data, headers=headers, method='PUT')
    with urllib.request.urlopen(request, timeout=180) as response:
        if not 200 <= response.status < 300:
            raise RuntimeError('Storage upload failed')


def publish(package, key, feed, endpoint, bucket, setup=None, uploader=put):
    immutable = 'public, max-age=31536000, immutable'
    uploader(endpoint, bucket, key, package.read_bytes(), 'application/zip', immutable)
    checksum = (feed['package']['sha256'] + '  UtilityClient-Update.zip\n').encode()
    uploader(endpoint, bucket, key + '.sha256', checksum, 'text/plain', immutable)
    if setup:
        uploader(endpoint, bucket, 'UtilityClient-Setup-Updated.exe', setup.read_bytes(), 'application/octet-stream', 'no-cache')
    # Switch the live feed only after all artifacts were accepted by storage.
    uploader(endpoint, bucket, 'update.json', (json.dumps(feed, indent=2) + '\n').encode(), 'application/json', 'no-store')


def main():
    config = settings()
    parser = argparse.ArgumentParser()
    parser.add_argument('--package', type=Path, required=True)
    parser.add_argument('--setup', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--public-base-url', default=config.get('publicBaseUrl'))
    parser.add_argument('--endpoint', default=config.get('endpoint'))
    parser.add_argument('--bucket', default=config.get('bucket'))
    parser.add_argument('--upload', action='store_true')
    args = parser.parse_args()
    if not args.public_base_url:
        parser.error('Set --public-base-url or UTILITYCLIENT_UPDATE_PUBLIC_BASE_URL')
    key, feed = prepare(args.package, args.public_base_url, args.output)
    if args.upload:
        if not args.endpoint or not args.bucket:
            parser.error('Uploading requires an endpoint and bucket')
        os.environ.setdefault('AWS_REGION', config.get('region', 'auto'))
        publish(args.package, key, feed, args.endpoint, args.bucket, args.setup)
    print(json.dumps({'status': 'published' if args.upload else 'prepared', 'manifest': args.public_base_url.rstrip('/') + '/update.json',
                      'build': feed['build'], 'sha256': feed['package']['sha256']}))


if __name__ == '__main__':
    main()
