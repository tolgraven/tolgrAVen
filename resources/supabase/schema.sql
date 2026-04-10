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

create table if not exists auth_roles (
  role text not null,
  user_id text not null references site_users(id) on delete cascade,
  primary key (role, user_id)
);

create table if not exists blog_posts (
  doc_id text not null unique,
  id bigint primary key,
  permalink text,
  user_id text references site_users(id) on delete set null,
  title text not null,
  text text not null,
  tags text,
  score bigint,
  ts bigint not null,
  raw jsonb not null default '{}'::jsonb
);

create table if not exists blog_comments (
  id text primary key,
  seq_id bigint,
  parent_post bigint references blog_posts(id) on delete cascade,
  parent_comment text references blog_comments(id) on delete cascade,
  user_id text references site_users(id) on delete set null,
  title text,
  text text not null,
  score bigint default 0,
  path jsonb not null default '[]'::jsonb,
  ts bigint not null,
  raw jsonb not null default '{}'::jsonb
);

create index if not exists blog_comments_parent_post_idx
  on blog_comments (parent_post);

create index if not exists blog_comments_parent_comment_idx
  on blog_comments (parent_comment);

create index if not exists blog_comments_user_id_idx
  on blog_comments (user_id);

create table if not exists chat_messages (
  message_id text primary key,
  ts bigint not null,
  user_id text,
  text text,
  raw jsonb not null default '{}'::jsonb
);

create table if not exists service_configs (
  service text primary key,
  collection text not null,
  doc_id text not null,
  config jsonb not null default '{}'::jsonb
);

alter table site_users enable row level security;
alter table blog_posts enable row level security;
alter table blog_comments enable row level security;
alter table chat_messages enable row level security;

drop policy if exists "Public read site_users" on site_users;
create policy "Public read site_users"
  on site_users for select
  using (true);

drop policy if exists "Public read blog_posts" on blog_posts;
create policy "Public read blog_posts"
  on blog_posts for select
  using (true);

drop policy if exists "Public read blog_comments" on blog_comments;
create policy "Public read blog_comments"
  on blog_comments for select
  using (true);

drop policy if exists "Public read chat_messages" on chat_messages;
create policy "Public read chat_messages"
  on chat_messages for select
  using (true);

alter table site_users replica identity full;
alter table blog_posts replica identity full;
alter table blog_comments replica identity full;
alter table chat_messages replica identity full;

do $$
begin
  begin
    alter publication supabase_realtime add table site_users;
  exception
    when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table blog_posts;
  exception
    when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table blog_comments;
  exception
    when duplicate_object then null;
  end;
  begin
    alter publication supabase_realtime add table chat_messages;
  exception
    when duplicate_object then null;
  end;
end
$$;
