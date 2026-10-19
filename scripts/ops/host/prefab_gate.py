#!/usr/bin/env python3
"""Coolify-owned dependency prerequisite; native Coolify still deploys the site.

Only signed events for trusted branches may build. Recipe code runs inside a
bounded BuildKit worker, never in this Docker-socket-owning control process.
"""
import base64
from contextlib import contextmanager
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid

REPOSITORY = 'tolgraven/tolgrAVen'
REGISTRY = 'registry.bux.tolgraven.se/tolgraven/builder'
INPUTS = ['Dockerfile.builder', 'project.clj', 'package.json', 'package-lock.json', 'scripts/media/images.clj']
BUILDER = 'tolgraven-prefab'
ROOT = Path(os.environ.get('PREFAB_STATE', '/state'))
SHA = re.compile(r'[0-9a-f]{40}')
WORKERS = []


def event_target(event, payload):
    """Return an owned destination or relay other App events without building."""
    event = event.lower()
    if not isinstance(payload, dict) or not isinstance(payload.get('repository', {}), dict):
        raise ValueError('Invalid webhook payload')
    if payload.get('repository', {}).get('full_name') != REPOSITORY:
        return {'key': 'relay', 'relay': True}
    if event == 'push':
        if payload.get('ref') != 'refs/heads/master' or payload.get('deleted'):
            return None
        commit = payload.get('after', '')
        target = {'key': 'master', 'sha': commit, 'pr': 0}
    elif event == 'pull_request':
        pr = payload.get('pull_request', {})
        if pr.get('base', {}).get('ref') != 'master':
            return None
        if payload.get('action') not in ('opened', 'reopened', 'synchronize', 'closed'):
            return None
        if (pr.get('head', {}).get('repo', {}).get('full_name') != REPOSITORY
                or pr.get('head', {}).get('repo', {}).get('fork')
                or pr.get('author_association') not in ('OWNER', 'MEMBER', 'COLLABORATOR')):
            return None
        number = payload.get('number')
        if not isinstance(number, int) or isinstance(number, bool) or number <= 0:
            raise ValueError('Invalid pull request number')
        target = {'key': 'pr-' + str(number), 'sha': pr.get('head', {}).get('sha', ''),
                  'pr': number, 'closed': payload['action'] == 'closed'}
    else:
        return None
    if not SHA.fullmatch(target['sha']):
        raise ValueError('Invalid commit SHA')
    return target


@contextmanager
def database():
    connection = sqlite3.connect(ROOT / 'queue.sqlite', timeout=30)
    connection.row_factory = sqlite3.Row
    try:
        with connection:
            yield connection
    finally:
        connection.close()


def initialize():
    ROOT.mkdir(parents=True, exist_ok=True)
    (ROOT / 'logs').mkdir(exist_ok=True)
    with database() as db:
        db.execute('PRAGMA journal_mode=WAL')
        db.execute('CREATE TABLE IF NOT EXISTS jobs (id INTEGER PRIMARY KEY, delivery TEXT UNIQUE, '
                   'owner TEXT, event TEXT, body BLOB, target TEXT, state TEXT DEFAULT "queued", '
                   'attempts INTEGER DEFAULT 0, updated REAL, error TEXT)')
        db.execute('UPDATE jobs SET state="queued" WHERE state="running"')


def enqueue(delivery, event, body, target):
    with database() as db:
        # Durable delivery deduplication; a failed delivery may be explicitly retried.
        db.execute('INSERT INTO jobs (delivery,owner,event,body,target,updated) VALUES (?,?,?,?,?,?) '
                   'ON CONFLICT(delivery) DO UPDATE SET state=CASE WHEN state="failed" THEN "queued" ELSE state END',
                   (delivery, target['key'], event, body, json.dumps(target), time.time()))


def command(args, *, cwd=None, log=None, input=None, timeout=3600):
    result = subprocess.run(args, cwd=cwd, input=input, stdout=log or subprocess.PIPE,
                            stderr=subprocess.STDOUT if log else subprocess.PIPE, timeout=timeout)
    if result.returncode:
        # Commands never include credentials. Provider response bodies stay private.
        raise RuntimeError('Command failed: ' + ' '.join(str(x) for x in args[:4]))
    return result.stdout


def bridge(action, **values):
    """Use Coolify's App identity without exporting its private key or token."""
    script = Path('/app/prefab-gate.php').read_bytes()
    request = json.dumps({'action': action, **values}).encode()
    prefix = b"<?php $input=json_decode(base64_decode('" + base64.b64encode(request) + b"'),true); ?>\n"
    return json.loads(command(['docker', 'exec', '-i', 'coolify', 'php'], input=prefix + script, timeout=240))


def current(target):
    return bridge('current', sha=target['sha'], pr=target['pr'])['current']


def status(target, state, description):
    bridge('status', sha=target['sha'], state=state, description=description)


def read_blob(checkout, path):
    return command(['git', 'show', 'HEAD:' + path], cwd=checkout)


def checked_image(checkout):
    for path in [*INPUTS, 'Dockerfile', '.dockerignore']:
        if (Path(checkout) / path).is_symlink():
            raise ValueError('Build control inputs must be regular checkout files')
    digest = hashlib.sha256()
    for path in INPUTS:
        digest.update((path + '\0').encode())
        digest.update(read_blob(checkout, path))
    image = REGISTRY + ':deps-' + digest.hexdigest()[:16]
    recipe = read_blob(checkout, 'Dockerfile').decode()
    if re.findall(r'^ARG BUILDER_IMAGE=(.*)$', recipe, re.M) != [image]:
        raise ValueError('Stale Dockerfile prefab pin; run bb docker pin')
    def normalize(text):
        fragment = text.split('ARG MAVEN_SEED_IMAGE=', 1)[1].split('# Compile on the builder', 1)[0]
        return re.sub(r' AS prefab(?=\n)', '', re.sub(r'--platform=\$(?:BUILD|TARGET)PLATFORM ', '', fragment)).strip()
    if normalize(recipe) != normalize(read_blob(checkout, 'Dockerfile.builder').decode()):
        raise ValueError('Fallback prefab recipe differs from Dockerfile.builder')
    return image


def image_exists(image):
    result = subprocess.run(['docker', 'manifest', 'inspect', image], capture_output=True, timeout=60)
    if result.returncode == 0:
        return True
    if re.search(rb'manifest unknown|no such manifest', result.stderr, re.I):
        return False
    raise RuntimeError('Registry inspection failed; refusing to treat authentication/network failure as a cache miss')


def ensure_builder(log):
    result = subprocess.run(['docker', 'buildx', 'inspect', BUILDER], capture_output=True, timeout=30)
    if result.returncode:
        command(['docker', 'buildx', 'create', '--name', BUILDER, '--driver', 'docker-container',
                 '--driver-opt', 'cpu-period=100000,cpu-quota=150000,memory=4g,memory-swap=4g',
                 '--buildkitd-config', '/app/buildkitd.toml'], log=log)
    command(['docker', 'buildx', 'inspect', '--bootstrap', BUILDER], log=log)


def verify_runtime(checkout, image, arch, log):
    # BuildKit's bundled emulator checks foreign runtimes without installing
    # privileged binfmt handlers on the host. PR code never runs in this process.
    smoke = ('set -e; java -version; node --version; bb --version; '
             'if command -v javac || command -v lein; then exit 1; fi; '
             'test ! -d /root/.m2/repository; test ! -d /usr/src/app/node_modules; '
             'mkdir /tmp/codecs; convert -size 16x16 xc:red /tmp/codecs/image.png; '
             'bb /app/scripts/media/images.clj /tmp/codecs/image.png; '
             'identify /tmp/codecs/image.webp /tmp/codecs/image.avif')
    recipe = read_blob(checkout, 'Dockerfile') + ('\nFROM media-tools AS prefab-runtime-check\nRUN ' + smoke + '\n').encode()
    # A tracked symlink must never redirect a control-process write into /app.
    with tempfile.NamedTemporaryFile(prefix='runtime-', suffix='.Dockerfile', dir=ROOT) as file:
        file.write(recipe)
        file.flush()
        command(['docker', 'buildx', 'build', '--builder', BUILDER, '--output', 'type=cacheonly',
                 '--target', 'prefab-runtime-check', '--platform', 'linux/' + arch,
                 '--build-arg', 'BUILDER_IMAGE=' + image, '-f', file.name, '--progress=plain', '.'],
                cwd=checkout, log=log)


def prefab(checkout, log):
    image = checked_image(checkout)
    ensure_builder(log)
    for arch in ('arm64', 'amd64'):
        native = image + '-' + arch
        if not image_exists(native):
            previous = ROOT / 'last-image'
            prior = previous.read_text().strip() if previous.exists() else os.environ.get('PREFAB_SEED_IMAGE')
            seeds = ([prior + '-' + arch] if prior else []) + [REGISTRY + ':java21-node22-v2-' + arch]
            seed = next((candidate for candidate in seeds if image_exists(candidate)), 'empty-maven-seed')
            command(['docker', 'buildx', 'build', '--builder', BUILDER, '--platform', 'linux/' + arch,
                     '--build-arg', 'BUILDPLATFORM=linux/' + arch,
                     '--build-arg', 'MAVEN_SEED_IMAGE=' + seed, '-f', 'Dockerfile.builder',
                     '--push', '--provenance=false', '--sbom=false', '-t', native, '--progress=plain', '.'],
                    cwd=checkout, log=log, timeout=7200)
        verify_runtime(checkout, native, arch, log)
    # Also repair an incomplete index left by an interrupted/manual publication.
    command(['docker', 'buildx', 'imagetools', 'create', '-t', image, image + '-arm64', image + '-amd64'], log=log)
    previous = ROOT / 'last-image.new'
    previous.write_text(image + '\n')
    previous.replace(ROOT / 'last-image')
    return image


def promote(image, log):
    # Called only after checking the current master again, after a long build.
    command(['docker', 'buildx', 'imagetools', 'create', '-t', REGISTRY + ':java21-node22-v2', image], log=log)
    for arch in ('arm64', 'amd64'):
        command(['docker', 'buildx', 'imagetools', 'create', '--prefer-index=false',
                 '-t', REGISTRY + ':java21-node22-v2-' + arch, image + '-' + arch], log=log)
    command(['docker', 'buildx', 'imagetools', 'create', '--prefer-index=false',
             '-t', REGISTRY + ':java21-node22-v1', image + '-arm64'], log=log)


def forward(event, body, manual=False, require_accepted=True):
    if manual:
        payload = json.loads(body)
        bridge('preview', sha=payload['pull_request']['head']['sha'], pr=payload['number'],
               closed=payload['action'] == 'closed')
        return
    secret = os.environ['WEBHOOK_SECRET']
    request = urllib.request.Request('http://coolify:8080/webhooks/source/github/events',
        data=body, method='POST', headers={'Content-Type': 'application/json', 'X-GitHub-Event': event,
            'X-GitHub-Delivery': str(uuid.uuid4()), 'X-GitHub-Hook-Installation-Target-Id': os.environ['GITHUB_APP_ID'],
            'X-Hub-Signature-256': 'sha256=' + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()})
    with urllib.request.urlopen(request, timeout=60) as response:
        reply = response.read()
    if require_accepted and event in ('push', 'pull_request'):
        result = json.loads(reply)
        if not isinstance(result, list) or not any(x.get('status') in ('success', 'queued', 'skipped') for x in result):
            raise RuntimeError('Coolify did not accept deployment event')


def process(job):
    target = json.loads(job['target'])
    if target.get('relay'):
        forward(job['event'], job['body'], require_accepted=False)
        return
    if target.get('closed'):
        forward(job['event'], job['body'], manual=True)
        return
    if not current(target):
        return  # An older queued event must never deploy or replace a newer status.
    status(target, 'pending', 'Checking the exact prefab in Coolify')
    with (ROOT / 'logs' / (str(job['id']) + '.log')).open('ab', buffering=0) as log:
        with tempfile.TemporaryDirectory(prefix='checkout-', dir=ROOT) as directory:
            command(['git', 'init', '-q', directory], log=log)
            command(['git', 'fetch', '--depth=1', 'https://github.com/' + REPOSITORY + '.git', target['sha']],
                    cwd=directory, log=log)
            command(['git', 'checkout', '--detach', 'FETCH_HEAD'], cwd=directory, log=log)
            image = prefab(directory, log)
            if not current(target):
                return
            if not target['pr']:
                promote(image, log)
    if not current(target):
        return
    status(target, 'success', 'Verified ' + image.rsplit(':', 1)[1])
    forward(job['event'], job['body'], manual=bool(target['pr']))


def work(control=False):
    while True:
        with database() as db:
            # Close events take priority over queued builds; replay is safe after restart.
            db.execute('BEGIN IMMEDIATE')
            selector = '(COALESCE(json_extract(target,"$.closed"),0)=1 OR COALESCE(json_extract(target,"$.relay"),0)=1)'
            job = db.execute('SELECT * FROM jobs WHERE state="queued" AND updated<=? AND '
                             + (selector if control else 'NOT ' + selector) + ' ORDER BY id LIMIT 1',
                             (time.time(),)).fetchone()
            if job:
                db.execute('UPDATE jobs SET state="running",attempts=attempts+1 WHERE id=?', (job['id'],))
        if not job:
            time.sleep(1)
            continue
        try:
            process(job)
            state, error, delay = 'done', None, 0
        except Exception as exception:
            error = str(exception)[:300]
            state, delay = ('queued', 60) if job['attempts'] < 2 else ('failed', 0)
            target = json.loads(job['target'])
            if target.get('sha') and not target.get('closed'):
                try:
                    status(target, 'failure', 'Prefab gate failed; see Coolify gate logs')
                except Exception:
                    pass
            print(json.dumps({'job': job['id'], 'state': state, 'error': error}), flush=True)
        with database() as db:
            db.execute('UPDATE jobs SET state=?,error=?,updated=? WHERE id=?',
                       (state, error, time.time() + delay, job['id']))
        if state == 'done':
            print(json.dumps({'job': job['id'], 'owner': job['owner'], 'state': state}), flush=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        if self.path != '/health':
            super().log_message(format, *args)

    def do_GET(self):
        self.send_response((200 if all(worker.is_alive() for worker in WORKERS) else 503)
                           if self.path == '/health' else 404)
        self.end_headers()

    def do_POST(self):
        try:
            if self.path != '/github':
                self.send_error(404)
                return
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 5 * 1024 * 1024:
                self.send_error(413)
                return
            self.connection.settimeout(15)
            body = self.rfile.read(size)
            expected = 'sha256=' + hmac.new(os.environ['WEBHOOK_SECRET'].encode(), body, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, self.headers.get('X-Hub-Signature-256', '')):
                self.send_error(401)
                return
            event = self.headers.get('X-GitHub-Event', '').lower()
            target = event_target(event, json.loads(body))
            if target:
                delivery = self.headers.get('X-GitHub-Delivery', '')
                if not re.fullmatch(r'[a-zA-Z0-9-]{1,100}', delivery):
                    raise ValueError('Invalid delivery ID')
                enqueue(delivery, event, body, target)
            self.send_response(202)
            self.end_headers()
        except (ValueError, TypeError, KeyError):
            self.send_error(400)


if __name__ == '__main__':
    initialize()
    for control in (False, True):
        worker = threading.Thread(target=work, args=(control,), daemon=True)
        WORKERS.append(worker)
        worker.start()
    with ThreadingHTTPServer(('0.0.0.0', 8080), Handler) as server:
        server.timeout = 2
        while all(worker.is_alive() for worker in WORKERS):
            server.handle_request()
    # Let the container restart policy recover a failed queue worker as well.
    raise RuntimeError('Gate worker stopped')
