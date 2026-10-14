create table if not exists public.github_repositories (
  owner text not null,
  repo text not null,
  is_public boolean not null default false,
  complete boolean not null default false,
  commit_count bigint not null default 0,
  state jsonb not null default '{"stage":"metadata"}'::jsonb,
  next_at timestamptz not null default now(),
  lease_owner uuid,
  lease_until timestamptz,
  updated_at timestamptz not null default now(),
  primary key (owner, repo)
);
alter table public.github_repositories enable row level security;
revoke all on public.github_repositories from anon, authenticated;
grant select (owner,repo,is_public,complete,commit_count,updated_at) on public.github_repositories to anon, authenticated;
grant all on public.github_repositories to service_role;
drop policy if exists "Public archived repositories" on public.github_repositories;
create policy "Public archived repositories" on public.github_repositories for select using (is_public);
