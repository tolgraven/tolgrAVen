\set ON_ERROR_STOP on
create role anon;
create role authenticated;
create role service_role bypassrls;
create table public.site_users (id text primary key);
create table public.auth_roles (role text,user_id text);
create publication supabase_realtime;
create schema storage;
create table storage.buckets (id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
insert into storage.buckets values ('media-originals','media-originals',false,123456,array['image/png']);
begin;
\i resources/supabase/modules/site_media.sql
\i resources/supabase/modules/blog_posts.sql
commit;

-- The table is exposed explicitly, without exposing job ownership or source bytes.
do $$ begin
  if not has_column_privilege('anon','public.site_media','asset','select') or
     has_column_privilege('anon','public.site_media','owner_id','select') or
     has_function_privilege('anon','public.tolgraven_claim_media(text)','execute') then
    raise exception 'Invalid public media permissions';
  end if;
  if not (select public from storage.buckets where id='media-renditions') or
     (select public from storage.buckets where id='media-originals') then
    raise exception 'Invalid bucket visibility';
  end if;
  if (select count(*) from storage.buckets where 'image/svg+xml'=any(allowed_mime_types)) <> 2 or
     (select file_size_limit from storage.buckets where id='media-originals') <> 123456 then
    raise exception 'SVG bucket update changed existing policy or omitted MIME support';
  end if;
end $$;

-- Repeated intake deduplicates; PostgreSQL enforces queue capacity atomically.
do $$ declare i integer; begin
  for i in 1..64 loop
    perform public.tolgraven_queue_media(jsonb_build_object('id','job'||i,'owner_id','author',
      'source_key','upload:'||i,'source_type','upload','source_object','private/'||i,
      'source_ext','png','content_hash','hash'||i,'policy_version','v1'));
  end loop;
  perform public.tolgraven_queue_media(jsonb_build_object('id','job1','owner_id','author',
    'source_key','upload:1','source_type','upload','source_object','private/1',
    'source_ext','png','content_hash','hash1','policy_version','v1'));
  if (select count(*) from public.site_media) <> 64 then raise exception 'Duplicate queue job'; end if;
  begin
    perform public.tolgraven_queue_media(jsonb_build_object('id','overflow','source_key','overflow',
      'source_type','upload','source_object','private/o','source_ext','png','content_hash','o','policy_version','v1'));
    raise exception 'Expected bounded queue rejection';
  exception when sqlstate 'PT503' then null;
  end;
end $$;

-- A failed job cannot bypass capacity when an explicit retry requeues it.
do $$ begin
  update public.site_media set status='failed' where id='job63';
  perform public.tolgraven_queue_media(jsonb_build_object('id','replacement64','source_key','replacement64',
    'source_type','upload','source_object','private/r','source_ext','png','content_hash','r','policy_version','v1'));
  begin
    perform public.tolgraven_queue_media(jsonb_build_object('id','job63','owner_id','author',
      'source_key','upload:63','source_type','upload','source_object','private/63',
      'source_ext','png','content_hash','hash63','policy_version','v1'));
    raise exception 'Explicit retry bypassed bounded queue';
  exception when sqlstate 'PT503' then null;
  end;
end $$;

-- Lease reassignment rejects stale completion and eventually fails exhausted work.
do $$ declare job public.site_media; replacement public.site_media; begin
  select * into job from public.tolgraven_claim_media('first');
  update public.site_media set lease_until=now()-interval '1 second' where id=job.id;
  select * into replacement from public.tolgraven_claim_media('replacement');
  if replacement.id <> job.id or replacement.lease_owner <> 'replacement' then raise exception 'Lease recovery failed'; end if;
  update public.site_media set status='ready',canonical_src='https://storage.test/a.png',asset='{}'
    where id=job.id and status='processing' and lease_owner='first';
  if found then raise exception 'Stale worker committed'; end if;
  update public.site_media set attempts=3,lease_until=now()-interval '1 second' where id=job.id;
  perform public.tolgraven_claim_media('next');
  if (select status from public.site_media where id=job.id) <> 'failed' then raise exception 'Exhausted lease stuck'; end if;
end $$;

update public.site_media set status='ready',canonical_src='https://storage.test/ready.png',asset='{}' where id='job64';
set role anon;
do $$ begin
  if (select count(*) from public.site_media) <> 1 then raise exception 'Private job escaped RLS'; end if;
end $$;
reset role;

-- The optional new argument preserves old five-argument callers.
insert into public.site_users values ('author');
insert into public.auth_roles values ('bloggers','author');
select public.tolgraven_save_post('author',null,'Legacy caller','Body','tag');
select public.tolgraven_save_post('author',null,'Media caller','![image](https://storage.test/a.png)','tag','{"https://storage.test/a.png":{"id":"asset"}}');
do $$ begin
  if (select image_assets from public.blog_posts where title='Legacy caller') <> '{}'::jsonb or
     not (select image_assets ? 'https://storage.test/a.png' from public.blog_posts where title='Media caller') then
    raise exception 'Post media manifest persistence failed';
  end if;
end $$;
select 'media SQL lifecycle checks passed';
