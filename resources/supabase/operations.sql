-- Backend-only RPCs. The web server validates the access token with Supabase Auth
-- and selects the actor from administrator-controlled profile linkage.
create table if not exists public.comment_votes (
  user_id text not null references public.site_users(id) on delete cascade,
  comment_id text not null references public.blog_comments(id) on delete cascade,
  vote smallint not null check (vote between -1 and 1),
  primary key (user_id, comment_id)
);
create index if not exists comment_votes_comment_id_idx on public.comment_votes(comment_id);
alter table public.comment_votes enable row level security;
revoke all on public.comment_votes from anon, authenticated;
grant all on public.comment_votes to service_role;

create or replace function public.tolgraven_post_chat(p_actor text, p_text text)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
  v_id text := pg_catalog.gen_random_uuid()::text;
  v_ts bigint := floor(extract(epoch from clock_timestamp()) * 1000);
begin
  if p_actor is null or p_actor !~ '^[A-Za-z0-9_-]+$'
     or p_text is null or length(trim(p_text)) = 0 or length(p_text) > 4000 then
    raise sqlstate 'PT400' using message = 'Invalid chat message';
  end if;
  insert into public.site_users(id) values (p_actor) on conflict do nothing;
  insert into public.chat_messages(message_id, ts, user_id, text)
    values (v_id, v_ts, p_actor, p_text);
  return jsonb_build_object('id', v_id, 'time', v_ts, 'user', p_actor, 'text', p_text);
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

create or replace function public.tolgraven_set_comment_vote(
  p_actor text, p_comment_id text, p_vote smallint, p_legacy_vote smallint)
returns jsonb language plpgsql security invoker set search_path = '' as $$
declare
  v_author text;
  v_previous smallint;
  v_delta smallint;
  v_score bigint;
begin
  if p_actor is null or p_actor !~ '^[A-Za-z0-9_-]+$'
     or p_vote is null or p_vote not between -1 and 1
     or p_legacy_vote is null or p_legacy_vote not between -1 and 1 then
    raise sqlstate 'PT400' using message = 'Invalid vote';
  end if;
  select user_id into v_author from public.blog_comments where id = p_comment_id;
  if not found then
    raise sqlstate 'PT404' using message = 'Comment not found';
  end if;
  if v_author = p_actor then
    raise sqlstate 'PT403' using message = 'You cannot vote on your own comment';
  end if;
  insert into public.site_users(id) values (p_actor) on conflict do nothing;
  -- Lock both profiles in a stable order before the comment. This also avoids
  -- deadlocks when two authors vote on each other's comments concurrently.
  perform id from public.site_users where id in (p_actor, v_author) order by id for no key update;
  select score into v_score from public.blog_comments where id = p_comment_id for no key update;
  if not found then
    raise sqlstate 'PT404' using message = 'Comment not found';
  end if;
  select vote into v_previous from public.comment_votes
    where user_id = p_actor and comment_id = p_comment_id;
  if not found then
    -- Existing scores already include imported votes. The server obtains this
    -- baseline from the actor's private imported profile, never the request body.
    v_previous := p_legacy_vote;
  end if;
  v_delta := p_vote - v_previous;
  insert into public.comment_votes(user_id, comment_id, vote)
    values (p_actor, p_comment_id, p_vote)
    on conflict (user_id, comment_id) do update set vote = excluded.vote;
  -- Keep zero-vote rows: they mark the imported baseline as consumed.
  update public.blog_comments set score = coalesce(score, 0) + v_delta
    where id = p_comment_id returning score into v_score;
  update public.site_users set karma = coalesce(karma, 0) + v_delta where id = v_author;
  return jsonb_build_object('comment-id', p_comment_id, 'vote', p_vote, 'score', v_score);
end $$;

revoke all on function public.tolgraven_post_chat(text, text) from public, anon, authenticated;
revoke all on function public.tolgraven_create_comment(text, bigint, text, text, text) from public, anon, authenticated;
revoke all on function public.tolgraven_edit_comment(text, text, text, text) from public, anon, authenticated;
revoke all on function public.tolgraven_set_comment_vote(text, text, smallint, smallint) from public, anon, authenticated;
grant execute on function public.tolgraven_post_chat(text, text) to service_role;
grant execute on function public.tolgraven_create_comment(text, bigint, text, text, text) to service_role;
grant execute on function public.tolgraven_edit_comment(text, text, text, text) to service_role;
grant execute on function public.tolgraven_set_comment_vote(text, text, smallint, smallint) to service_role;
notify pgrst, 'reload schema';
