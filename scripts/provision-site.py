#!/usr/bin/env python3
"""Provision isolated Coolify site environments over the existing root SSH connection."""
import argparse
import base64
import json
import re
import shlex
import subprocess
import time
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
TABLES = ('site_users', 'auth_roles', 'blog_posts', 'blog_comments', 'chat_messages',
          'service_configs', 'store_documents', 'comment_votes', 'user_documents')


def validate(spec):
    if not re.fullmatch(r'[a-z][a-z0-9-]{1,48}', spec['name']):
        raise ValueError('name must be a lowercase site slug')
    for key in ('server_uuid', 'destination_uuid'):
        if not re.fullmatch(r'[a-z0-9]+', spec[key]):
            raise ValueError(f'Invalid {key}')
    if not re.fullmatch(r'[a-zA-Z0-9_.@-]+', spec['ssh_host']) or spec['ssh_host'].startswith('-'):
        raise ValueError('ssh_host must be an SSH hostname or configured alias')
    if spec.get('coolify_ssh_host') and (not re.fullmatch(r'[a-zA-Z0-9_.@-]+', spec['coolify_ssh_host']) or spec['coolify_ssh_host'].startswith('-')):
        raise ValueError('Invalid coolify_ssh_host')
    repository = urlparse(spec['repository'])
    if repository.scheme != 'https' or not repository.hostname or repository.username or repository.password:
        raise ValueError('repository must be an HTTPS Git URL without credentials')
    environments = spec['environments']
    if not environments or set(environments) - {'production', 'staging'}:
        raise ValueError('environments must contain production and/or staging')
    urls = set()
    for config in environments.values():
        for key in ('web_url', 'supabase_url'):
            url = urlparse(config[key])
            if (url.scheme != 'https' or not url.hostname or url.port or url.username or url.password
                    or url.path not in ('', '/') or url.query or url.fragment):
                raise ValueError(f'{key} must be an HTTPS origin without a port or path')
            normalized = config[key].rstrip('/')
            if normalized in urls:
                raise ValueError('Every web and Supabase endpoint must have a distinct origin')
            config[key] = normalized
            urls.add(normalized)
        if not re.fullmatch(r'[A-Za-z0-9_./-]+', config['branch']) or config['branch'].startswith('-'):
            raise ValueError('Invalid Git branch')
        if any(not isinstance(value, str) for value in config.get('supabase_env', {}).values()):
            raise ValueError('supabase_env values must be strings')
        if config.get('application_uuid') and not spec.get('project_uuid'):
            raise ValueError('An existing application requires an explicit project_uuid')
    return spec


def php_script(spec, action):
    payload = base64.b64encode(json.dumps({'spec': spec, 'action': action}).encode()).decode()
    helper = (ROOT / 'scripts/provision-site.php').read_text()
    return "<?php $input=json_decode(base64_decode('" + payload + "'),true); ?>\n" + helper


def ssh(spec, args, data=None, timeout=120, host=None):
    result = subprocess.run(['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10',
                             host or spec['ssh_host'], shlex.join(args)], input=data,
                            text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        # Inputs may be SQL containing data; never echo the command or stdin.
        raise RuntimeError(result.stderr.strip() or 'Remote command failed')
    return result.stdout


def remote(spec, action):
    return json.loads(ssh(spec, ['docker', 'exec', '-i', 'coolify', 'php'], php_script(spec, action),
                          host=spec.get('coolify_ssh_host', spec['ssh_host'])))


def sql(spec, database, query):
    if not re.fullmatch(r'supabase-db-[a-z0-9]+', database):
        raise ValueError('Invalid database container name')
    return ssh(spec, ['docker', 'exec', '-i', database, 'psql', '-X', '-qAt',
                      '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres'], query)


def schema(spec, state):
    script = '\n'.join((ROOT / 'resources/supabase' / name).read_text()
                       for name in ('schema.sql', 'operations.sql'))
    for name, env in state['environments'].items():
        database = env['database_container']
        for attempt in range(120):
            try:
                ready = sql(spec, database, "select to_regclass('auth.users') is not null;").strip() == 't'
                if ready:
                    break
            except RuntimeError:
                pass
            time.sleep(5)
        else:
            raise RuntimeError(f'{name}: database did not become ready; inspect the Supabase service in Coolify')
        sql(spec, database, 'BEGIN;\n' + script + '\nCOMMIT;')
        result = sql(spec, database,
                     "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace "
                     "where n.nspname='public' and c.relname in (" +
                     ','.join("'" + table + "'" for table in TABLES) + ') and c.relrowsecurity;').strip()
        if result != str(len(TABLES)):
            raise RuntimeError(f'{name}: schema verification failed')
        print(f'{name}: schema, RPCs and RLS verified ({result} tables)', flush=True)


def plan(spec):
    return {'project': spec.get('project_uuid', spec['name']), 'server': spec['server_uuid'],
            'data': 'empty: schema and policies only; no production data or users are copied',
            'environments': {name: {'web': config['web_url'], 'supabase': config['supabase_url'],
                                    'git_branch': config['branch'], 'separate_database_and_storage': True}
                             for name, config in spec['environments'].items()}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'probe', 'prepare', 'start', 'schema', 'wire', 'deploy', 'status', 'ready', 'verify', 'up'])
    parser.add_argument('manifest', type=Path)
    parser.add_argument('--emit-php', action='store_true', help='Render a Coolify-container script without executing it')
    args = parser.parse_args()
    spec = validate(json.loads(args.manifest.read_text()))
    if args.action == 'plan':
        print(json.dumps(plan(spec), indent=2))
        return
    if args.emit_php:
        if any(config.get('supabase_env') for config in spec['environments'].values()):
            parser.error('--emit-php is disabled for manifests with Supabase configuration values')
        if args.action in ('schema', 'up'):
            parser.error('--emit-php is supported only for individual Coolify actions')
        print(php_script(spec, args.action))
        return
    if args.action == 'up':
        state = remote(spec, 'prepare')
        print(json.dumps(state, indent=2), flush=True)
        remote(spec, 'start')
        schema(spec, state)
        print('Waiting for Supabase HTTPS, REST and Auth readiness...', flush=True)
        deadline = time.monotonic() + 600
        while True:
            try:
                remote(spec, 'ready')
                break
            except RuntimeError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(5)
        remote(spec, 'wire')
        result = remote(spec, 'deploy')
        print(json.dumps(result, indent=2), flush=True)
        pending = {name: item['deployment']['deployment_uuid'] for name, item in result['environments'].items()}
        for attempt in range(180):
            state = remote(spec, 'status')
            for name, item in state['environments'].items():
                deployment = item.get('latest_deployment') or {}
                if name in pending:
                    if deployment.get('deployment_uuid') != pending[name]:
                        raise RuntimeError(f'{name}: another deployment superseded this run; inspect Coolify')
                    status = deployment.get('status')
                    if status == 'finished':
                        del pending[name]
                    elif status in ('failed', 'cancelled', 'cancelled-by-user'):
                        raise RuntimeError(f'{name}: deployment {status}; inspect Coolify')
            if not pending:
                break
            time.sleep(5)
        else:
            raise RuntimeError('Deployment still running; use status before retrying')
        result = remote(spec, 'verify')
    elif args.action == 'schema':
        state = remote(spec, 'status')
        schema(spec, state)
        result = state
    else:
        result = remote(spec, args.action)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, RuntimeError, subprocess.TimeoutExpired) as error:
        raise SystemExit(str(error)) from None
