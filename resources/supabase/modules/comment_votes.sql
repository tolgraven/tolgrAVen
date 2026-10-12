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

revoke all on function public.tolgraven_set_comment_vote(text, text, smallint, smallint) from public, anon, authenticated;

grant execute on function public.tolgraven_set_comment_vote(text, text, smallint, smallint) to service_role;
