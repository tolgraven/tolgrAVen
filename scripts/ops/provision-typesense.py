#!/usr/bin/env python3
"""Prepare and verify an environment-bound private Typesense service in Coolify."""
import argparse
import base64
import json
import re
import shlex
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'start', 'verify', 'wire', 'status', 'migrate'])
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--dry-run', action='store_true', help='Roll back preparation without starting or wiring a service')
    args = parser.parse_args()
    spec = json.loads(args.manifest.read_text())
    host = spec.pop('ssh_host', 'bux')
    if not re.fullmatch(r'[A-Za-z0-9_.@-]+', host) or host.startswith('-'):
        parser.error('Invalid SSH host')
    spec['action'] = args.action
    if args.dry_run and args.action != 'prepare':
        parser.error('--dry-run is supported only for prepare')
    spec['dry_run'] = args.dry_run
    encoded = base64.b64encode(json.dumps(spec).encode()).decode()
    helper = Path(__file__).parent / 'coolify' / (
        'import-typesense.php' if args.action == 'migrate' else 'provision-typesense.php')
    script = "<?php $input=json_decode(base64_decode('" + encoded + "'),true); ?>\n" + helper.read_text()
    subprocess.run(['ssh', '-o', 'ConnectTimeout=10', host,
                    shlex.join(['docker', 'exec', '-i', 'coolify', 'php'])],
                   input=script, text=True, check=True, timeout=120)


if __name__ == '__main__':
    main()
