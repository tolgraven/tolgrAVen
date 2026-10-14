create table if not exists public.github_commits (
  owner text not null,
  repo text not null,
  sha text not null check (sha ~ '^[a-fA-F0-9]{40,64}$'),
  message text not null,
  authored_at timestamptz not null,
  committed_at timestamptz not null,
  author_name text,
  author_login text,
  avatar_url text,
  html_url text not null,
  category text,
  subcategory text,
  indexed_at timestamptz not null default now(),
  search_vector tsvector generated always as
    (to_tsvector('simple', coalesce(message,'') || ' ' || coalesce(author_name,'') || ' ' || coalesce(author_login,'') || ' ' || sha)) stored,
  primary key (owner, repo, sha),
  foreign key (owner,repo) references public.github_repositories(owner,repo)
);
create index if not exists github_commits_search on public.github_commits using gin(search_vector);
create index if not exists github_commits_order on public.github_commits(owner,repo,committed_at desc,sha);
create index if not exists github_commits_export on public.github_commits(indexed_at,owner,repo,sha);
alter table public.github_commits enable row level security;
revoke all on public.github_commits from anon, authenticated;
grant select (owner,repo,sha,message,authored_at,committed_at,author_name,author_login,avatar_url,html_url,category,subcategory,indexed_at) on public.github_commits to anon, authenticated;
grant all on public.github_commits to service_role;
drop policy if exists "Public archived commits" on public.github_commits;
create policy "Public archived commits" on public.github_commits for select using
  (exists (select 1 from public.github_repositories r where r.owner=github_commits.owner and r.repo=github_commits.repo and r.is_public));

-- One expiring lease per repository across app instances. The checkpoint advances
-- in the same transaction as its commit page: crashes never skip unwritten data.
create or replace function public.website_claim_github(p_owner text,p_repo text,p_worker uuid)
returns setof public.github_repositories language plpgsql security invoker set search_path='' as $$
begin
  insert into public.github_repositories(owner,repo) values(p_owner,p_repo) on conflict do nothing;
  return query update public.github_repositories set lease_owner=p_worker,lease_until=now()+interval '2 minutes'
    where owner=p_owner and repo=p_repo and next_at<=now() and (lease_until is null or lease_until<now()) returning *;
end $$;

create or replace function public.website_finish_github(p_owner text,p_repo text,p_worker uuid,p_state jsonb,
  p_rows jsonb,p_public boolean,p_complete boolean,p_delay integer)
returns boolean language plpgsql security invoker set search_path='' as $$
begin
  perform 1 from public.github_repositories where owner=p_owner and repo=p_repo
    and lease_owner=p_worker and lease_until>now() for update;
  if not found then return false; end if;
  if jsonb_array_length(p_rows)>100 or p_delay<0 or p_delay>86400 then raise exception 'Invalid archive batch'; end if;
  insert into public.github_commits(owner,repo,sha,message,authored_at,committed_at,author_name,author_login,avatar_url,html_url,category,subcategory)
    select p_owner,p_repo,c.sha,c.message,c.authored_at,c.committed_at,c.author_name,c.author_login,c.avatar_url,c.html_url,c.category,c.subcategory
    from jsonb_to_recordset(p_rows) as c(sha text,message text,authored_at timestamptz,committed_at timestamptz,
      author_name text,author_login text,avatar_url text,html_url text,category text,subcategory text)
    on conflict(owner,repo,sha) do nothing;
  update public.github_repositories set state=p_state,is_public=p_public,complete=p_complete,
    commit_count=(select count(*) from public.github_commits where owner=p_owner and repo=p_repo),
    next_at=now()+make_interval(secs=>p_delay),lease_owner=null,lease_until=null,updated_at=now()
    where owner=p_owner and repo=p_repo;
  return true;
end $$;
revoke all on function public.website_claim_github(text,text,uuid) from public,anon,authenticated;
revoke all on function public.website_finish_github(text,text,uuid,jsonb,jsonb,boolean,boolean,integer) from public,anon,authenticated;
grant execute on function public.website_claim_github(text,text,uuid) to service_role;
grant execute on function public.website_finish_github(text,text,uuid,jsonb,jsonb,boolean,boolean,integer) to service_role;

-- Backend-only search keeps provider-shaped responses and private job state out
-- of the client contract. Raw rows remain available for a later Typesense export.
create or replace function public.website_search_github(p_owner text,p_repo text,p_query text default '',
  p_before_time timestamptz default null,p_before_sha text default null)
returns jsonb language sql stable security invoker set search_path='' as $$
  with matches as (
    select c.* from public.github_commits c join public.github_repositories r using(owner,repo)
    where c.owner=p_owner and c.repo=p_repo and r.is_public
      and (length(trim(p_query))=0 or c.search_vector @@ websearch_to_tsquery('simple',left(p_query,300)))
  ), candidates as (
    select * from matches where p_before_time is null or committed_at<p_before_time
      or (committed_at=p_before_time and sha>p_before_sha)
    order by committed_at desc,sha limit 31
  ), page as (
    select * from candidates order by committed_at desc,sha limit 30
  )
  select jsonb_build_object('commits',coalesce((select jsonb_agg(to_jsonb(page)-'search_vector') from page),'[]'::jsonb),
    'next-cursor',case when (select count(*) from candidates)>30 then
      (select (to_jsonb(committed_at)#>>'{}') || '|' || sha from page order by committed_at asc,sha desc limit 1) else null end,
    'total',(select count(*) from matches),'complete',coalesce((select complete from public.github_repositories where owner=p_owner and repo=p_repo and is_public),false));
$$;
revoke all on function public.website_search_github(text,text,text,timestamptz,text) from public,anon,authenticated;
grant execute on function public.website_search_github(text,text,text,timestamptz,text) to service_role;
