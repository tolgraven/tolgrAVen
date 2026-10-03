"""Auth import integration checks for a disposable Supabase-compatible Postgres DB.

Usage: python3 test/sql/supabase_auth_import_test.py -h /tmp -p 55432 -U postgres -d postgres
The database needs auth.users, auth.identities and the application schema.
Fixtures are local to this test and removed on success or failure.
"""
import json, subprocess, os, sys
from pathlib import Path
root = Path(__file__).resolve().parents[2]
psql = [os.environ.get('PSQL', 'psql'), '-X', '-v', 'ON_ERROR_STOP=1', '-At', *sys.argv[1:]]

def query(sql, ok=True):
    r = subprocess.run(psql, input=sql, text=True, capture_output=True)
    if ok and r.returncode:
        raise Exception('SQL test failed: ' + r.stderr[:500])
    if not ok and r.returncode == 0:
        raise Exception('Expected SQL rejection')
    return r.stdout.strip()
fixture = {'id': 'a1000000-0000-0000-0000-000000000001', 'firebase_uid': 'auth-import-fixture-1', 'email': 'first@example.invalid', 'encrypted_password': 'fixture-hash', 'created_at': '2020-01-01T00:00:00Z', 'updated_at': '2026-10-03T00:00:00Z', 'last_sign_in_at': None, 'email_confirmed_at': None, 'banned_until': None, 'name': 'Imported', 'avatar': None, 'app_metadata': {'provider': 'email', 'providers': ['email'], 'firebase_uid': 'auth-import-fixture-1', 'firebase_project_id': 'test-project', 'site_user_id': 'auth-import-fixture-1'}, 'user_metadata': {}, 'identities': [{'provider': 'email', 'provider_id': 'a1000000-0000-0000-0000-000000000001', 'identity_data': {'sub': 'a1000000-0000-0000-0000-000000000001', 'email': 'first@example.invalid', 'email_verified': False}}]}
second = json.loads(json.dumps(fixture))
second.update(id='a1000000-0000-0000-0000-000000000002', firebase_uid='auth-import-fixture-2', email=None, encrypted_password=None)
second['app_metadata'].update(site_user_id=second['firebase_uid'], firebase_uid=second['firebase_uid'], provider='github', providers=['github'])
second['identities'] = [{'provider': 'github', 'provider_id': 'github-fixture-subject', 'identity_data': {'sub': 'github-fixture-subject', 'email_verified': False}}]
body = (root / 'resources/supabase/auth-import.sql').read_text()

def run(accounts, ok=True):
    stage = "BEGIN; CREATE TEMP TABLE firebase_auth_import (data jsonb NOT NULL) ON COMMIT DROP; INSERT INTO firebase_auth_import VALUES ('" + json.dumps(accounts).replace("'", "''") + "'::jsonb);\n"
    return query(stage + body, ok)

def count():
    return query("SELECT count(*) FROM auth.users WHERE raw_app_meta_data->>'firebase_project_id'='test-project';")
assert count() == '0', 'Test project accounts already exist; use a disposable DB.'
assert query("SELECT count(*) FROM site_users WHERE id IN ('auth-import-fixture-1','auth-import-fixture-2');") == '0', 'Test profile IDs already exist.'
assert query("SELECT count(*) FROM auth.users WHERE id IN ('a1000000-0000-0000-0000-000000000001','a1000000-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000003');") == '0', 'Test account IDs already exist.'
try:
    query('INSERT INTO site_users (id,name,karma,comment_count,raw) VALUES (\'auth-import-fixture-1\',\'Original\',123,44,\'{"legacy":true}\');')
    before = query("SELECT row_to_json(p) FROM site_users p WHERE id='auth-import-fixture-1';")
    run([fixture, second])
    assert count() == '2'
    assert query("SELECT row_to_json(p) FROM site_users p WHERE id='auth-import-fixture-1';") == before
    assert query("SELECT count(*) FROM auth.identities WHERE user_id IN ('a1000000-0000-0000-0000-000000000001','a1000000-0000-0000-0000-000000000002');") == '2'
    assert query("SELECT email IS NULL AND is_anonymous=false FROM auth.users WHERE id='a1000000-0000-0000-0000-000000000002';") == 't'
    query("UPDATE auth.users SET encrypted_password='new-bcrypt-hash',email_confirmed_at=now(),banned_until='2099-01-01' WHERE id='a1000000-0000-0000-0000-000000000001';")
    security = query("SELECT encrypted_password,email_confirmed_at,banned_until FROM auth.users WHERE id='a1000000-0000-0000-0000-000000000001';")
    run([fixture, second])
    assert count() == '2'
    assert query("SELECT encrypted_password,email_confirmed_at,banned_until FROM auth.users WHERE id='a1000000-0000-0000-0000-000000000001';") == security
    collision = json.loads(json.dumps(fixture))
    collision['id'] = 'a1000000-0000-0000-0000-000000000003'
    run([collision], False)
    assert count() == '2'
    collision['email'] = 'other@example.invalid'
    collision['identities'] = second['identities']
    run([collision], False)
    assert count() == '2'
    query('UPDATE auth.users SET raw_app_meta_data=jsonb_set(raw_app_meta_data,\'{site_user_id}\',\'"other-owner"\') WHERE id=\'a1000000-0000-0000-0000-000000000001\';')
    run([fixture, second], False)
    assert count() == '2'
    print('Auth SQL checks passed: account/identity insertion, no-email OAuth, profile preservation, idempotency, password/verification/ban preservation, email/provider/account conflicts and atomic rejection.')
finally:
    query("DELETE FROM auth.users WHERE id IN ('a1000000-0000-0000-0000-000000000001','a1000000-0000-0000-0000-000000000002','a1000000-0000-0000-0000-000000000003'); DELETE FROM site_users WHERE id IN ('auth-import-fixture-1','auth-import-fixture-2');")
