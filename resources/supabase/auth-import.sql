-- The staging table is temporary. Abort the whole import on ownership conflicts.
-- Lock target tables so a concurrent signup cannot race these checks.
LOCK TABLE auth.users, auth.identities, public.site_users IN SHARE ROW EXCLUSIVE MODE;
CREATE TEMP VIEW firebase_auth_accounts AS
  SELECT account FROM firebase_auth_import, jsonb_array_elements(data) AS account;
DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1 FROM firebase_auth_accounts s JOIN auth.users u
      ON u.id = (s.account->>'id')::uuid
    WHERE u.raw_app_meta_data->>'firebase_uid' IS DISTINCT FROM s.account->>'firebase_uid'
       OR u.raw_app_meta_data->>'firebase_project_id' IS DISTINCT FROM s.account->'app_metadata'->>'firebase_project_id'
       OR u.raw_app_meta_data->>'site_user_id' IS DISTINCT FROM s.account->>'firebase_uid'
  ) THEN RAISE EXCEPTION 'Auth import account ID ownership conflict'; END IF;
  IF EXISTS (
    SELECT 1 FROM firebase_auth_accounts s JOIN auth.users u
      ON lower(u.email) = s.account->>'email' AND u.id <> (s.account->>'id')::uuid
  ) THEN RAISE EXCEPTION 'Auth import email ownership conflict'; END IF;
  IF EXISTS (
    SELECT 1 FROM firebase_auth_accounts s,
      jsonb_array_elements(s.account->'identities') AS i
      JOIN auth.identities existing ON existing.provider = i->>'provider'
                                   AND existing.provider_id = i->>'provider_id'
    WHERE existing.user_id <> (s.account->>'id')::uuid
  ) THEN RAISE EXCEPTION 'Auth import provider ownership conflict'; END IF;
END $migration$;

INSERT INTO auth.users (
  instance_id, id, aud, role, email, encrypted_password, email_confirmed_at,
  created_at, updated_at, last_sign_in_at, raw_app_meta_data, raw_user_meta_data,
  banned_until, confirmation_token, recovery_token, email_change_token_new,
  email_change, email_change_token_current, reauthentication_token,
  is_super_admin, is_sso_user, is_anonymous
)
SELECT '00000000-0000-0000-0000-000000000000'::uuid, (account->>'id')::uuid,
  'authenticated', 'authenticated', account->>'email', coalesce(account->>'encrypted_password', ''),
  (account->>'email_confirmed_at')::timestamptz, (account->>'created_at')::timestamptz,
  (account->>'updated_at')::timestamptz, (account->>'last_sign_in_at')::timestamptz,
  account->'app_metadata', account->'user_metadata', (account->>'banned_until')::timestamptz,
  '', '', '', '', '', '', false, false, false
FROM firebase_auth_accounts
ON CONFLICT (id) DO NOTHING;

INSERT INTO auth.identities (user_id, provider_id, provider, identity_data,
                            created_at, updated_at, last_sign_in_at)
SELECT (s.account->>'id')::uuid, i->>'provider_id', i->>'provider', i->'identity_data',
  (s.account->>'created_at')::timestamptz, (s.account->>'updated_at')::timestamptz,
  (s.account->>'last_sign_in_at')::timestamptz
FROM firebase_auth_accounts s, jsonb_array_elements(s.account->'identities') AS i
ON CONFLICT (provider_id, provider) DO NOTHING;

-- Existing profiles keep their names, counters, roles and ownership unchanged.
INSERT INTO public.site_users (id, name, email, avatar)
SELECT account->>'firebase_uid', account->>'name', account->>'email', account->>'avatar'
FROM firebase_auth_accounts
ON CONFLICT (id) DO NOTHING;

DO $migration$
BEGIN
  IF EXISTS (
    SELECT 1 FROM firebase_auth_accounts s LEFT JOIN auth.users u ON u.id = (s.account->>'id')::uuid
      LEFT JOIN public.site_users p ON p.id = s.account->>'firebase_uid'
    WHERE u.id IS NULL OR p.id IS NULL
       OR u.raw_app_meta_data->>'site_user_id' IS DISTINCT FROM p.id
  ) THEN RAISE EXCEPTION 'Auth import profile linkage verification failed'; END IF;
END $migration$;
DROP VIEW firebase_auth_accounts;
COMMIT;
SELECT count(*) AS imported_accounts,
       count(*) FILTER (WHERE encrypted_password LIKE '$fbscrypt$%') AS firebase_passwords,
       count(*) FILTER (WHERE email_confirmed_at IS NOT NULL) AS verified_emails
FROM auth.users WHERE raw_app_meta_data ? 'firebase_uid';
