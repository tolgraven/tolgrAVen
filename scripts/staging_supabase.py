#!/usr/bin/env python3
"""On-demand lifecycle for tolgraven's staging Supabase on bux only.

The runtime policy calls reconcile(); manual image deployments call ensure.
Stop preserves containers, networks, volumes and data. Failed observations never
mean idle. The separate production service is never a lifecycle target.
"""
import argparse
from contextlib import contextmanager
import fcntl
import json
from pathlib import Path
import re
import subprocess
import time

STAGING = 'o84wgo08wcs048ss8sokgkgw'
SUPABASE = 'fqaammdsestcbglokp8ewao0'
COMPOSE = Path('/data/coolify/services') / SUPABASE / 'docker-compose.yml'
MANUAL = Path('/var/lib/tolgraven/manual-deploy.json')
STATE = Path('/var/lib/tolgraven/staging-supabase.json')
LOCK = Path('/var/lock/tolgraven-staging-supabase.lock')
IDLE_SECONDS = 60
BOOTSTRAP = ('<?php require "/var/www/html/vendor/autoload.php"; '
             '$app=require "/var/www/html/bootstrap/app.php"; '
             '$app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap(); ')


def run(*args, timeout=45, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True,
                          timeout=timeout, **kwargs).stdout


def php(code):
    return json.loads(run('docker', 'exec', '-i', 'coolify', 'php', input=BOOTSTRAP + code))


def pending():
    # Failing to reach Coolify must not shut down a database that may be in use.
    return php('$a=App\\Models\\Application::where("uuid","' + STAGING + '")->firstOrFail(); '
               'echo json_encode($a->deployment_queue()->whereIn("status",'
               '["queued","in_progress"])->exists());')


def web_running(names):
    return any(re.fullmatch(re.escape(STAGING) + r'(?:-\d+|-pr-\d+)?', name) for name in names)


def observation():
    active = pending()
    names = run('docker', 'ps', '--format', '{{.Names}}').splitlines()
    return active or web_running(names) or MANUAL.exists()


def stack_running():
    return bool(run('docker', 'ps', '-q', '--filter',
                    'label=com.docker.compose.project=' + SUPABASE).strip())


def ready():
    # Keys remain inside Coolify. Test both the database-backed REST API and Auth.
    return php('$s=App\\Models\\Service::where("uuid","' + SUPABASE + '")->firstOrFail(); '
               '$key=$s->environment_variables()->where("key","SERVICE_SUPABASEANON_KEY")->firstOrFail()->value; '
               '$url=$s->environment_variables()->where("key","SERVICE_URL_SUPABASEKONG")->firstOrFail()->value; '
               '$ok=true; foreach (["/auth/v1/health","/rest/v1/blog_posts?select=id&limit=1"] as $path) {'
               'try {$ok=$ok && Illuminate\\Support\\Facades\\Http::timeout(5)->withHeaders(["apikey"=>$key])->get(rtrim($url,"/").$path)->successful();}'
               'catch (Throwable $e) {$ok=false;}} echo json_encode($ok);')


def compose(*args):
    if not COMPOSE.is_file():
        raise RuntimeError('Staging Compose file is missing; refusing lifecycle operation')
    return run('docker', 'compose', '--project-name', SUPABASE, '--file', str(COMPOSE),
               *args, timeout=300)


@contextmanager
def locked():
    with LOCK.open('w') as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        yield


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save_state(state):
    STATE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(state))
    temporary.chmod(0o600)
    temporary.replace(STATE)


def start_and_wait():
    if ready():
        return
    print('Starting staging Supabase; retaining existing volumes.', flush=True)
    compose('up', '-d', '--no-recreate', '--pull', 'never')
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if ready():
            print('Staging Supabase Auth and REST are ready.', flush=True)
            return
        time.sleep(3)
    raise RuntimeError('Staging Supabase failed readiness; inspect its Coolify service')


def ensure():
    with locked():
        # Protect the gap before an explicit deployment reaches Coolify's queue.
        save_state({'lease_until': time.time() + 300})
        start_and_wait()


def reconcile():
    with locked():
        state = load_state()
        now = time.time()
        active = observation()
        if active or state.get('lease_until', 0) > now:
            if active:
                state.pop('lease_until', None)
            state.pop('idle_since', None)
            save_state(state)
            start_and_wait()
        else:
            idle_since = state.setdefault('idle_since', now)
            save_state(state)
            if now - idle_since >= IDLE_SECONDS and stack_running():
                # Recheck immediately before stopping, including manual-deploy state.
                if observation():
                    state.pop('idle_since', None)
                    save_state(state)
                    return
                print('No staging runtime or deployment for 60 seconds; stopping staging Supabase.', flush=True)
                compose('stop', '--timeout', '30')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['ensure', 'reconcile'])
    args = parser.parse_args()
    ensure() if args.action == 'ensure' else reconcile()
