create table if not exists site_users (
  id text primary key,
  seq_id bigint,
  name text,
  email text,
  avatar text,
  bg_color text,
  comment_count bigint default 0,
  karma bigint default 0,
  comments jsonb not null default '[]'::jsonb,
  voted jsonb not null default '{}'::jsonb,
  raw jsonb not null default '{}'::jsonb
);

alter table site_users enable row level security;

revoke all on site_users from anon, authenticated;

grant select (id, seq_id, name, avatar, bg_color, comment_count, karma)
  on site_users to anon, authenticated;

grant all on site_users to service_role;

drop policy if exists "Public read site_users" on site_users;

create policy "Public read site_users"
  on site_users for select
  using (true);

alter table site_users replica identity full;

do $$ begin
  begin
    alter publication supabase_realtime add table public.site_users;
  exception when duplicate_object then null;
  end;
end $$;
