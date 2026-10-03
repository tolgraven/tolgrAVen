#!/usr/bin/env python3
"""One-time database seed into a verified, empty staging Supabase on the same host.

Copies site tables, Auth accounts/identities and storage bucket metadata.
Sessions, refresh tokens, audit logs and production infrastructure credentials
are not copied. Nonempty object storage requires a separate object transfer.
"""
import argparse
import base64
import json
import re
import subprocess
import sys

TABLES = ['public.site_users', 'public.auth_roles', 'public.blog_posts', 'public.blog_comments',
          'public.chat_messages', 'public.service_configs', 'public.store_documents',
          'public.comment_votes', 'public.user_documents', 'auth.users', 'auth.identities',
          'storage.buckets', 'storage.objects']
BUCKET_QUERY = "select coalesce(jsonb_agg(to_jsonb(b) order by id),'[]'::jsonb) from (select id,name,public,file_size_limit,allowed_mime_types,owner from storage.buckets) b;"
TRANSIENT_TOKENS = ['confirmation_token', 'recovery_token', 'email_change_token_new',
                    'email_change_token_current', 'phone_change_token', 'reauthentication_token']


def run(args, data=None):
    result = subprocess.run(args, input=data, capture_output=True, text=True, timeout=180)
    if result.returncode:
        # SQL errors may echo records, so do not print the raw failing statement.
        detail = next((line for line in result.stderr.splitlines() if 'ERROR:' in line or 'error:' in line), 'See database logs')
        detail = re.sub(r"'[^']*'", "'[redacted]'", detail)[:200]
        raise RuntimeError('Remote database operation failed: '+detail)
    return result.stdout


def sql(container, query):
    return run(['docker', 'exec', '-i', container, 'psql', '-X', '-qAt',
                '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres'], query)


def ownership(source, target):
    if source == target or not all(re.fullmatch(r'[a-z0-9]+', value) for value in (source, target)):
        raise ValueError('Distinct source and target service UUIDs are required')
    payload = base64.b64encode(json.dumps([source, target]).encode()).decode()
    php = """<?php require '/var/www/html/vendor/autoload.php';
$app=require '/var/www/html/bootstrap/app.php';
$app->make(Illuminate\\Contracts\\Console\\Kernel::class)->bootstrap();
$ids=json_decode(base64_decode('PAYLOAD'),true);
$s=App\\Models\\Service::where('uuid',$ids[0])->firstOrFail();
$t=App\\Models\\Service::where('uuid',$ids[1])->firstOrFail();
if ($t->environment->name !== 'staging' || $s->server_id !== $t->server_id
    || $s->environment->project->team_id !== $t->environment->project->team_id
    || !str_starts_with($t->description ?? '', 'Managed by scripts/provision-site.py: '))
    throw new Exception('Target must be a provisioner-owned staging service on the same server/team');
echo json_encode(['source'=>$s->uuid,'target'=>$t->uuid,'environment'=>$t->environment->name]);
""".replace('PAYLOAD', payload)
    return json.loads(run(['docker', 'exec', '-i', 'coolify', 'php'], php))


def counts(container):
    return {table: int(sql(container, 'select count(*) from '+table+';').strip()) for table in TABLES}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-service', required=True)
    parser.add_argument('--target-service', required=True)
    args = parser.parse_args()
    scope = ownership(args.source_service, args.target_service)
    source = 'supabase-db-' + args.source_service
    target = 'supabase-db-' + args.target_service
    before = counts(source)
    if before['storage.objects']:
        raise RuntimeError('Source has storage objects: transfer the object payloads before using this database-only seed')
    for table in ('auth.mfa_factors', 'auth.sso_providers'):
        if int(sql(source, 'select count(*) from '+table+';').strip()):
            raise RuntimeError(f'{table} contains provider/security records needing an explicit migration')
    target_counts = counts(target)
    if any(count for table, count in target_counts.items() if table != 'storage.buckets'):
        raise RuntimeError('Target contains data; refusing to overwrite or merge it')
    # operations.sql creates the empty avatars bucket. Only that exact bootstrap
    # configuration may be retained, and only if it matches the source bucket.
    target_buckets = json.loads(sql(target, BUCKET_QUERY))
    source_buckets = {row['id']: row for row in json.loads(sql(source, BUCKET_QUERY))}
    if any(row['id'] != 'avatars' or row != source_buckets.get(row['id']) for row in target_buckets):
        raise RuntimeError('Target has non-bootstrap bucket configuration; refusing to merge it')
    dump_args = ['docker', 'exec', source, 'pg_dump', '-U', 'postgres', '-d', 'postgres',
                 '--data-only', '--column-inserts', '--on-conflict-do-nothing', '--no-owner', '--no-privileges']
    for table in TABLES:
        dump_args.extend(['--table', table])
    # The dump stays in memory on the server; no production-data archive is left on disk.
    dump = run(dump_args)
    empty_guard = '\n'.join("IF EXISTS (SELECT 1 FROM "+table+") THEN RAISE EXCEPTION 'Target no longer empty'; END IF;" for table in TABLES if table != 'storage.buckets')
    bucket_literal = json.dumps(target_buckets).replace("'", "''")
    bucket_guard = "IF ("+BUCKET_QUERY.rstrip(';')+") IS DISTINCT FROM '"+bucket_literal+"'::jsonb THEN RAISE EXCEPTION 'Bucket configuration changed'; END IF;"
    clear_tokens = 'UPDATE auth.users SET ' + ', '.join(column+" = ''" for column in TRANSIENT_TOKENS) + ';'
    query = ('BEGIN;\nLOCK TABLE '+', '.join(TABLES)+' IN ACCESS EXCLUSIVE MODE;\n'
             'DO $$ BEGIN '+empty_guard+bucket_guard+' END $$;\nSET LOCAL session_replication_role = replica;\n'
             + dump + '\n' + clear_tokens + '\nCOMMIT;')
    sql(target, query)
    after = counts(target)
    if before != after:
        raise RuntimeError('Post-copy row counts differ; do not wire staging until reviewed')
    for table in TABLES[:9]:
        query = "select md5(coalesce(string_agg(row_data, '' order by row_data),'')) from (select to_jsonb(t)::text row_data from "+table+' t) q;'
        if sql(source, query) != sql(target, query):
            raise RuntimeError(f'{table} content verification failed; production may have changed during the copy')
    print(json.dumps({'scope': scope, 'copied_rows': after, 'public_content_checksums_match': True,
                      'sessions_copied': False, 'storage_objects': 0}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        sys.exit(str(error))
