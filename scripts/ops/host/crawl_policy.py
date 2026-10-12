#!/usr/bin/env python3
"""Prepare/apply owned nginx crawl includes through CapRover's persisted templates.

Run as root on the requested host. Plan never writes; apply validates candidate
nginx before persisting sources/reloading. Backups contain private CapRover data.
"""
import argparse
import copy
import fcntl
import json
import os
import re
import subprocess
import time
from pathlib import Path

INCLUDES = '/nginx-shared/tolgraven-crawl-policy'
HTTP_INCLUDE = f'include {INCLUDES}/http.conf;'
SERVER_INCLUDE = f'include {INCLUDES}/server.conf;'
HTTP = r'''map "$host:$uri" $tolgraven_crawl_robots {
    volatile;
    default 0;
    ~*^([a-z0-9-]+\.)*(bux|box)\.tolgraven\.se:/robots\.txt$ 1;
}
'''
SERVER = r'''# Rewrite only the private server zones. Public hosts retain their usual route.
if ($tolgraven_crawl_robots) {
    rewrite ^ /__tolgraven_server_robots last;
}
location = /__tolgraven_server_robots {
    internal;
    default_type text/plain;
    charset utf-8;
    add_header Cache-Control "public, max-age=300";
    return 200 "User-agent: *\nDisallow: /\n";
}
'''


def command(*args):
    return subprocess.run(args, check=True, text=True, capture_output=True, timeout=60).stdout


def inject(text, context, include):
    """Keep original templates intact; add an include to every owned context."""
    pattern = re.compile(r'(^[ \t]*' + re.escape(context) + r'\s*\{)(?!\s*' + re.escape(include) + r')', re.M)
    result = pattern.sub(lambda m: m.group(1) + '\n    ' + include, text)
    if not re.search(r'^[ \t]*' + re.escape(context) + r'\s*\{', result, re.M):
        raise ValueError(f'No {context} context found')
    return result


def patch_config(config, default_base, default_root):
    result = copy.deepcopy(config)
    result['nginxBaseConfig'] = inject(result.get('nginxBaseConfig') or default_base, 'http', HTTP_INCLUDE)
    result['nginxCaptainConfig'] = inject(result.get('nginxCaptainConfig') or default_root, 'server', SERVER_INCLUDE)
    for app in result['appDefinitions'].values():
        if app.get('customNginxConfig'):
            app['customNginxConfig'] = inject(app['customNginxConfig'], 'server', SERVER_INCLUDE)
    return result


def private_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, 'w') as output:
        output.write(data)


def snapshot(path):
    return (path.read_bytes(), path.stat().st_mode & 0o777) if path.exists() else None


def restore(path, saved):
    if saved is None:
        path.unlink(missing_ok=True)
    else:
        # Generated files are bind-mounted by inode; never replace them.
        path.write_bytes(saved[0])
        path.chmod(saved[1])


def apply_policy(config_path, override, generated, shared, original, updated,
                 updated_override, nginx, backup):
    files = [generated / 'nginx.conf', *sorted((generated / 'conf.d').glob('*.conf'))]
    generated_sources = {path: path.read_text() for path in files}
    saved = {path: snapshot(path) for path in
             [config_path, override, *files, shared / 'http.conf', shared / 'server.conf']}
    backup.mkdir(parents=True, mode=0o700)
    for i, (path, state) in enumerate(saved.items()):
        if state is not None:
            private_write(backup / f'{i}-{path.name}', state[0].decode())
    private_write(backup / 'manifest.json', json.dumps({str(path): {
        'backup': f'{i}-{path.name}' if state is not None else None,
        'mode': state[1] if state is not None else None,
    } for i, (path, state) in enumerate(saved.items())}, indent=2) + '\n')
    shared.mkdir(parents=True, exist_ok=True)
    persisted = False
    try:
        (shared / 'http.conf').write_text(HTTP)
        (shared / 'server.conf').write_text(SERVER)
        for path, text in generated_sources.items():
            path.write_text(inject(text, 'http' if path.name == 'nginx.conf' else 'server',
                                   HTTP_INCLUDE if path.name == 'nginx.conf' else SERVER_INCLUDE))
        command('docker', 'exec', nginx, 'nginx', '-t')
        if config_path.read_text() != original:
            raise ValueError('CapRover configuration changed during preparation; retry from fresh state')
        # Mark before either write so partial persistence is restored too.
        persisted = True
        private_write(config_path, json.dumps(updated, indent=2) + '\n')
        private_write(override, updated_override)
        command('docker', 'exec', nginx, 'nginx', '-s', 'reload')
        command('docker', 'service', 'update', '--force', 'captain-captain')
        print('Applied; private recovery backup: ' + str(backup))
    except Exception:
        for path, state in saved.items():
            # A concurrent CapRover edit detected before our write belongs to it.
            if path == config_path and not persisted:
                continue
            restore(path, state)
        if persisted:
            command('docker', 'exec', nginx, 'nginx', '-s', 'reload')
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'apply'])
    args = parser.parse_args()
    if args.action == 'apply' and os.geteuid() != 0:
        raise ValueError('Apply requires root on the host')
    # Serialize our installers; still detect independent CapRover writes below.
    lock = None
    if args.action == 'apply':
        lock = open('/run/tolgraven-crawl-policy.lock', 'w')
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    container = command('docker', 'ps', '--filter', 'name=captain-captain', '--format', '{{.ID}}').strip()
    nginx = command('docker', 'ps', '--filter', 'name=captain-nginx', '--format', '{{.ID}}').strip()
    if not container or not nginx or '\n' in container + nginx:
        raise ValueError('Expected one existing CapRover and nginx container')
    template = lambda name: command('docker', 'exec', container, 'cat', '/usr/src/app/template/' + name)
    loader = command('docker', 'exec', container, 'cat', '/usr/src/app/built/datastore/DataStore.js')
    if 'server-block-conf-override.ejs' not in loader:
        raise ValueError('Installed CapRover does not support the persistent app template override')
    config_path = Path('/captain/data/config-captain.json')
    original = config_path.read_text()
    updated = patch_config(json.loads(original), template('base-nginx-conf.ejs'), template('root-nginx-conf.ejs'))
    override = Path('/captain/data/server-block-conf-override.ejs')
    previous_override = override.read_text() if override.exists() else None
    updated_override = inject(previous_override or template('server-block-conf.ejs'), 'server', SERVER_INCLUDE)
    print(json.dumps({'action': args.action, 'zones': ['bux.tolgraven.se', 'box.tolgraven.se'],
                      'custom_app_templates': sum(bool(a.get('customNginxConfig')) for a in updated['appDefinitions'].values()),
                      'future_app_template': str(override), 'restart': 'captain-captain only; nginx reload'}))
    if args.action == 'plan':
        return
    backup = Path('/captain/data/tolgraven-crawl-policy-backups') / str(time.time_ns())
    generated = Path('/captain/generated/nginx')
    shared = Path('/captain/data/nginx-shared/tolgraven-crawl-policy')
    apply_policy(config_path, override, generated, shared, original, updated,
                 updated_override, nginx, backup)


if __name__ == '__main__':
    main()
