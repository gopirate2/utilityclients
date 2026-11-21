"""Configure Scaleway Object Storage once; credentials remain in environment variables."""
import argparse
import json
from pathlib import Path
import re
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--bucket', required=True)
parser.add_argument('--region', choices=('fr-par', 'nl-ams', 'pl-waw'), required=True)
parser.add_argument('--public-base-url', help='Optional public CDN/domain for this bucket')
args = parser.parse_args()
if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', args.bucket):
    parser.error('Use a valid Scaleway bucket name')
base = args.public_base_url or 'https://' + args.bucket + '.s3.' + args.region + '.scw.cloud'
parsed = urllib.parse.urlsplit(base)
if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
    parser.error('The public base URL must use HTTPS without credentials')
config = {'provider': 'scaleway', 'bucket': args.bucket, 'region': args.region,
          'endpoint': 'https://s3.' + args.region + '.scw.cloud', 'publicBaseUrl': base.rstrip('/')}
path = Path(__file__).with_name('update-server.local.json')
path.write_text(json.dumps(config, indent=2) + '\n', encoding='utf-8')
print('Configured public update manifest: ' + config['publicBaseUrl'] + '/update.json')
print('Build/publish credentials are read from AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY.')
