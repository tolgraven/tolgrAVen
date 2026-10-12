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

alter table blog_comments enable row level security;

revoke all on blog_comments from anon, authenticated;

grant select (id, seq_id, parent_post, parent_comment, user_id, title, text, score, path, ts)
  on blog_comments to anon, authenticated;

grant all on blog_comments to service_role;

drop policy if exists "Public read blog_comments" on blog_comments;

create policy "Public read blog_comments"
  on blog_comments for select
  using (true);

alter table blog_comments replica identity full;

do $$ begin
  begin
    alter publication supabase_realtime add table public.blog_comments;
  exception when duplicate_object then null;
  end;
end $$;

create or replace function public.tolgraven_create_comment(
  p_actor text, p_post_id bigint, p_parent_id text, p_text text, p_title text)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
  v_id text := pg_catalog.gen_random_uuid()::text;
  v_ts bigint := floor(extract(epoch from clock_timestamp()) * 1000);
  v_path jsonb;
  v_parent public.blog_comments%rowtype;
begin
  if p_actor is null or p_actor !~ '^[A-Za-z0-9_-]+$'
     or p_text is null or length(trim(p_text)) = 0 or length(p_text) > 20000
     or length(p_title) > 200 then
    raise sqlstate 'PT400' using message = 'Invalid comment';
  end if;
  perform id from public.blog_posts where id = p_post_id for key share;
  if not found then
    raise sqlstate 'PT404' using message = 'Post not found';
  end if;
  v_path := jsonb_build_array(p_post_id);
  if p_parent_id is not null then
    select * into v_parent from public.blog_comments where id = p_parent_id for key share;
    if not found or v_parent.parent_post is distinct from p_post_id then
      raise sqlstate 'PT400' using message = 'Reply must belong to the same post';
    end if;
    v_path := v_parent.path || jsonb_build_array(v_parent.id);
  end if;
  insert into public.site_users(id) values (p_actor) on conflict do nothing;
  insert into public.blog_comments(id, parent_post, parent_comment, user_id, title, text, path, ts)
    values (v_id, p_post_id, p_parent_id, p_actor, p_title, p_text, v_path, v_ts);
  update public.site_users
    set comments = comments || jsonb_build_array(v_id),
        comment_count = coalesce(comment_count, 0) + 1
    where id = p_actor;
  return jsonb_build_object('id', v_id, 'path', v_path, 'ts', v_ts);
end $$;

create or replace function public.tolgraven_edit_comment(
  p_actor text, p_comment_id text, p_text text, p_title text)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare v_owner text;
begin
  if p_text is null or length(trim(p_text)) = 0 or length(p_text) > 20000
     or length(p_title) > 200 then
    raise sqlstate 'PT400' using message = 'Invalid comment';
  end if;
  select user_id into v_owner from public.blog_comments where id = p_comment_id for no key update;
  if not found then
    raise sqlstate 'PT404' using message = 'Comment not found';
  end if;
  if p_actor is null or v_owner is distinct from p_actor then
    raise sqlstate 'PT403' using message = 'Only the author can edit this comment';
  end if;
  update public.blog_comments set text = p_text, title = p_title where id = p_comment_id;
  return jsonb_build_object('id', p_comment_id);
end $$;

revoke all on function public.tolgraven_create_comment(text, bigint, text, text, text) from public, anon, authenticated;

revoke all on function public.tolgraven_edit_comment(text, text, text, text) from public, anon, authenticated;

grant execute on function public.tolgraven_create_comment(text, bigint, text, text, text) to service_role;

grant execute on function public.tolgraven_edit_comment(text, text, text, text) to service_role;
