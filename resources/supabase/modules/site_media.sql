create table if not exists public.site_media (
  id text primary key,
  owner_id text,
  source_key text not null,
  source_type text not null check (source_type in ('upload','strapi')),
  source_url text,
  canonical_src text,
  source_object text not null,
  source_ext text not null,
  content_hash text not null,
  policy_version text not null,
  status text not null default 'queued' check (status in ('queued','processing','ready','failed')),
  asset jsonb,
  metadata jsonb not null default '{}'::jsonb,
  active boolean not null default true,
  attempts integer not null default 0,
  lease_owner text,
  lease_until timestamptz,
  next_attempt timestamptz not null default now(),
  error text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (source_key,content_hash,policy_version),
  check (status <> 'ready' or (asset is not null and canonical_src is not null))
);
-- Replace the existing named check as well as covering fresh installations.
alter table public.site_media drop constraint if exists site_media_source_ext_check;
alter table public.site_media add constraint site_media_source_ext_check
  check (source_ext in ('png','jpg','webp','avif','svg'));

create index if not exists site_media_source_url on public.site_media (source_url) where status='ready';
create index if not exists site_media_canonical_src on public.site_media (canonical_src) where status='ready';
create index if not exists site_media_queue on public.site_media (status,lease_until,created_at);
alter table public.site_media enable row level security;
revoke all on public.site_media from anon,authenticated;
grant select (id,source_url,canonical_src,status,asset,updated_at) on public.site_media to anon,authenticated;
grant all on public.site_media to service_role;
drop policy if exists site_media_ready_read on public.site_media;
create policy site_media_ready_read on public.site_media for select to anon,authenticated using (status='ready');

-- Only trusted server callers can enqueue or claim jobs. PostgreSQL owns both
-- queue capacity and leases, so restarts/replicas cannot race an in-memory queue.
create or replace function public.tolgraven_queue_media(p_job jsonb) returns setof public.site_media
language plpgsql security invoker set search_path=public as $$
begin
  perform pg_advisory_xact_lock(hashtext('tolgraven.site_media.queue'));
  if not exists (select 1 from site_media where id=p_job->>'id' and
       ((active and status in ('queued','processing')) or status='ready')) and
     (select count(*) from site_media where active and status in ('queued','processing')) >= 64 then
    raise exception using errcode='PT503',message='Media queue is busy';
  end if;
  return query insert into site_media (id,owner_id,source_key,source_type,source_url,source_object,source_ext,content_hash,policy_version,metadata)
    values (p_job->>'id',p_job->>'owner_id',p_job->>'source_key',p_job->>'source_type',p_job->>'source_url',p_job->>'source_object',p_job->>'source_ext',p_job->>'content_hash',p_job->>'policy_version',coalesce(p_job->'metadata','{}'::jsonb))
    on conflict (id) do update set metadata=excluded.metadata,active=true,updated_at=now(),
      source_url=coalesce(excluded.source_url,site_media.source_url),
      status=case when site_media.status='failed' then 'queued' else site_media.status end,
      attempts=case when site_media.status='failed' then 0 else site_media.attempts end,
      next_attempt=case when site_media.status='failed' then now() else site_media.next_attempt end,
      asset=case when site_media.status='ready' then jsonb_set(site_media.asset,'{alt}',coalesce(excluded.metadata->'alt','""'::jsonb)) else site_media.asset end
    returning site_media.*;
end $$;
create or replace function public.tolgraven_claim_media(p_worker text) returns setof public.site_media
language plpgsql security invoker set search_path=public as $$
begin
  update site_media set status='failed',error='Media worker lease expired'
    where status='processing' and attempts >= 3 and lease_until < now();
  return query update site_media set status='processing',lease_owner=p_worker,
    lease_until=now()+interval '15 minutes',attempts=attempts+1,updated_at=now()
  where id=(select id from site_media where active and attempts < 3 and
    ((status='queued' and next_attempt <= now()) or (status='processing' and lease_until < now()))
    order by case when status='processing' then 0 else 1 end,created_at,id for update skip locked limit 1)
  returning site_media.*;
end $$;
revoke all on function public.tolgraven_queue_media(jsonb) from public,anon,authenticated;
revoke all on function public.tolgraven_claim_media(text) from public,anon,authenticated;
grant execute on function public.tolgraven_queue_media(jsonb) to service_role;
grant execute on function public.tolgraven_claim_media(text) to service_role;

-- Source bytes are private; only completed immutable renditions are public.
do $$ begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types)
      values ('media-originals','media-originals',false,5242880,array['image/png','image/jpeg','image/webp','image/avif','image/svg+xml']),
             ('media-renditions','media-renditions',true,67108864,array['image/png','image/jpeg','image/webp','image/avif','image/svg+xml'])
      on conflict(id) do update set allowed_mime_types=excluded.allowed_mime_types;
  end if;
end $$;
