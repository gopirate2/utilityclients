"""Non-secret update hosting settings shared by packaging and publishing."""
import json
import os
from pathlib import Path


def settings():
    path = Path(__file__).with_name('update-server.local.json')
    configured = json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}
    names = {'publicBaseUrl': 'UTILITYCLIENT_UPDATE_PUBLIC_BASE_URL', 'endpoint': 'UTILITYCLIENT_UPDATE_STORAGE_ENDPOINT',
             'bucket': 'UTILITYCLIENT_UPDATE_BUCKET', 'region': 'AWS_REGION'}
    for name, variable in names.items():
        if os.environ.get(variable):
            configured[name] = os.environ[variable]
    return configured
