#!/usr/bin/env python3
"""Deploy an S3 registry image through the existing Coolify staging application.

Installed on bux. Uses the installed Coolify 4.x queue helper, not a second
runtime manager. Root SSH access is required; no additional API token is stored.
"""
import argparse
import base64
import fcntl
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

STAGING = 'o84wgo08wcs048ss8sokgkgw'
IMAGE_PREFIX = '127.0.0.1:5005/tolgraven/site:'
STATE = Path('/var/lib/tolgraven/manual-deploy.json')
FIELDS = ['build_pack', 'docker_registry_image_name', 'docker_registry_image_tag']


def run(*args, capture=False, **kwargs):
    return subprocess.run(args, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, **kwargs).stdout


def php(code, payload=None):
    data = base64.b64encode(json.dumps(payload or {}).encode()).decode()
    source = '<?php require "/var/www/html/vendor/autoload.php"; $app = require "/var/www/html/bootstrap/app.php"; $app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap(); '
    source += '$data=json_decode(base64_decode("' + data + '"),true); '
    source += '$a=App\\Models\\Application::where("uuid","' + STAGING + '")->firstOrFail(); '
    source += code
    return json.loads(run('docker', 'exec', '-i', 'coolify', 'php', input=source, capture=True))


def staging_containers():
    ids = run('docker', 'ps', '-q', capture=True).split()
    if not ids:
        return []
    # Exact application names only: never stop production, Supabase, or build helpers.
    return [c for c in json.loads(run('docker', 'inspect', *ids, capture=True))
            if re.fullmatch(re.escape(STAGING) + r'(?:-\d+|-pr-\d+)?', c['Name'].lstrip('/'))]


def stop(containers):
    for container in containers:
        print('Stopping staging:', container['Name'], flush=True)
        run('docker', 'update', '--restart=no', container['Id'], capture=True)
        run('docker', 'stop', '--time', '30', container['Id'], capture=True)


def restore(state):
    php('$a->forceFill($data["fields"])->save(); '
        '$a->settings->is_auto_deploy_enabled=$data["auto"]; $a->settings->save(); '
        'echo "true";', state)


def deploy(image, pr):
    if not re.fullmatch(re.escape(IMAGE_PREFIX) + r'[A-Za-z0-9_][A-Za-z0-9_.-]*', image):
        raise SystemExit('Only the private tolgraven staging image repository is allowed')
    if STATE.exists():
        raise SystemExit(f'An unfinished manual deployment exists. Inspect {STATE} before retrying.')
    # A failed pull must not interrupt the running site.
    run('docker', 'pull', image)
    image_id = json.loads(run('docker', 'image', 'inspect', image, capture=True))[0]['Id']
    state = php('if ($a->deployment_queue()->whereIn("status", ["queued", "in_progress"])->exists()) '
                '{throw new Exception("Staging already has an active deployment");} '
                'if ($data["pr"] && !App\\Models\\ApplicationPreview::findPreviewByApplicationAndPullId($a->id,$data["pr"])) '
                '{throw new Exception("Requested staging preview does not exist");} '
                'echo json_encode(["fields"=>$a->only($data["fields"]), '
                '"auto"=>$a->settings->is_auto_deploy_enabled]);', {'fields': FIELDS, 'pr': pr})
    # Wake the retained staging database before interrupting the old web runtime.
    run('python3', '/usr/local/lib/tolgraven/staging_supabase.py', 'ensure')
    previous = staging_containers()
    # Keep rollback metadata, not environment variables/secrets.
    state['previous'] = [{'Id': c['Id'], 'Created': c['Created'],
                          'restart': c['HostConfig']['RestartPolicy']['Name']} for c in previous]
    deployment = str(uuid.uuid4())
    state.update(deployment=deployment, image=image, pr=pr)
    STATE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    STATE.write_text(json.dumps(state, indent=2))
    STATE.chmod(0o600)
    terminal = False
    queued = False
    try:
        repo, tag = image.rsplit(':', 1)
        php('$a->settings->is_auto_deploy_enabled=false; $a->settings->save(); '
            '$a->forceFill(["build_pack"=>"dockerimage", "docker_registry_image_name"=>$data["repo"], '
            '"docker_registry_image_tag"=>$data["tag"]])->save(); echo "true";', {'repo':repo,'tag':tag})
        stop(previous)
        # If the queue call has an uncertain outcome, retain recovery state.
        queued = True
        result = php('echo json_encode(queue_application_deployment(application:$a, '
                     'deployment_uuid:$data["deployment"], pull_request_id:$data["pr"], '
                     'docker_registry_image_tag:$data["tag"]));',
                     {'deployment':deployment, 'pr':pr, 'tag':tag})
        print('Coolify deployment:', deployment, result, flush=True)
        for _ in range(180):
            status = php('echo json_encode(App\\Models\\ApplicationDeploymentQueue::where('
                         '"deployment_uuid",$data["deployment"])->value("status"));', {'deployment':deployment})
            if status in ('finished', 'failed', 'cancelled', 'cancelled-by-user'):
                terminal = True
                break
            time.sleep(5)
        if not terminal:
            raise RuntimeError(f'Deployment still active or unknown: {status}; configuration retained for recovery')
        if status != 'finished':
            raise RuntimeError(f'Coolify deployment {status}')
        running = staging_containers()
        if len(running) != 1 or running[0]['Image'] != image_id:
            raise RuntimeError('Expected exactly one staging container using the new image')
        # Verify HTTP separately from Coolify reporting successful startup.
        for path in ('/', '/api/supabase/settings'):
            run('docker', 'exec', running[0]['Id'], 'curl', '--fail', '--silent',
                '--header', 'X-Forwarded-Proto: https', '--output', '/dev/null', '--retry', '12', '--retry-connrefused',
                '--retry-delay', '2', '--max-time', '15', 'http://127.0.0.1:3000' + path)
        restore(state)
        STATE.unlink()
        print('Verified one staging runtime and HTTP endpoints; original Git build configuration restored.', flush=True)
    except BaseException:
        # Do not restore configuration/start old containers while a Coolify job can still race us.
        if terminal or not queued:
            stop(staging_containers())
            restore(state)
            if state['previous']:
                old = max(state['previous'], key=lambda c: c['Created'])
                run('docker', 'update', '--restart=' + old['restart'], old['Id'], capture=True)
                run('docker', 'start', old['Id'], capture=True)
                print('Restored the previous staging runtime.', flush=True)
            STATE.unlink()
        else:
            print(f'Inspect deployment {deployment}; recovery metadata is in {STATE}.', flush=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image')
    parser.add_argument('--pr', type=int, default=0)
    args = parser.parse_args()
    if args.pr < 0:
        parser.error('--pr must be zero or a positive preview number')
    with open('/var/lock/tolgraven-manual-deploy.lock', 'w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        deploy(args.image, args.pr)


if __name__ == '__main__':
    main()
