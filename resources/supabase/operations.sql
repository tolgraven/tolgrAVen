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


-- Owner documents replace the old shared GPT documents. Browser reads and row
-- changes use the same trusted profile claim as the application backend.
create table if not exists public.user_documents (
  owner_id text not null references public.site_users(id) on delete cascade,
  collection text not null check (collection in ('gpt', 'gpt-threads')),
  doc_id text not null,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  primary key (owner_id, collection, doc_id)
);
alter table public.user_documents enable row level security;
revoke all on public.user_documents from anon, authenticated;
grant select on public.user_documents to authenticated;
grant all on public.user_documents to service_role;
drop policy if exists "Owner reads documents" on public.user_documents;
create policy "Owner reads documents" on public.user_documents for select to authenticated
  using (owner_id = coalesce(auth.jwt()->'app_metadata'->>'site_user_id', auth.uid()::text));
alter table public.user_documents replica identity full;
do $$ begin
  if not exists (select 1 from pg_publication_tables where pubname='supabase_realtime'
                 and schemaname='public' and tablename='user_documents') then
    alter publication supabase_realtime add table public.user_documents;
  end if;
end $$;
-- Only explicitly owned legacy threads are transferred; shared or anonymous
-- documents are kept private in the archive rather than assigned to a caller.
insert into public.user_documents (owner_id, collection, doc_id, data)
select d.data->>'user', d.collection, d.doc_id, d.data
from public.store_documents d join public.site_users u on u.id=d.data->>'user'
where d.collection='gpt-threads'
on conflict do nothing;

create or replace function public.tolgraven_save_document(
  p_actor text, p_collection text, p_doc_id text, p_data jsonb, p_merge boolean
) returns jsonb language plpgsql security invoker set search_path='' as $$
begin
  if p_collection not in ('gpt','gpt-threads') or p_doc_id !~ '^[A-Za-z0-9_-]+$'
     or jsonb_typeof(p_data) <> 'object' or octet_length(p_data::text)>500000 then
    raise sqlstate 'PT400' using message='Invalid private document';
  end if;
  insert into public.user_documents as target (owner_id,collection,doc_id,data)
  values (p_actor,p_collection,p_doc_id,p_data || jsonb_build_object('user',p_actor))
  on conflict (owner_id,collection,doc_id) do update
    set data=(case when p_merge then target.data || excluded.data else excluded.data end), updated_at=now();
  return (select data from public.user_documents
          where owner_id=p_actor and collection=p_collection and doc_id=p_doc_id);
end $$;
revoke all on function public.tolgraven_save_document(text,text,text,jsonb,boolean) from public,anon,authenticated;
grant execute on function public.tolgraven_save_document(text,text,text,jsonb,boolean) to service_role;

create sequence if not exists public.blog_post_id_seq as bigint;
-- Applying this script is transactional; prevent a concurrent publisher from
-- taking a sequence value while its imported baseline is established.
lock table public.blog_posts in share row exclusive mode;
select setval('public.blog_post_id_seq', greatest(coalesce((select max(id) from public.blog_posts),0),
                                                 (select last_value from public.blog_post_id_seq),1));
grant usage,select on sequence public.blog_post_id_seq to service_role;
create or replace function public.tolgraven_save_post(
  p_actor text, p_post_id bigint, p_title text, p_text text, p_tags text
) returns jsonb language plpgsql security invoker set search_path='' as $$
declare post public.blog_posts; identifier bigint; permalink text;
begin
  if not exists (select 1 from public.auth_roles where user_id=p_actor and role in ('admins','bloggers')) then
    raise sqlstate 'PT403' using message='Publishing role required';
  end if;
  if p_title is null or length(btrim(p_title))=0 or length(p_title)>200
     or p_text is null or length(btrim(p_text))=0 or length(p_text)>200000
     or length(p_tags)>2000 then
    raise sqlstate 'PT400' using message='Invalid post';
  end if;
  if p_post_id is not null then
    select * into post from public.blog_posts where id=p_post_id for update;
    if not found then raise sqlstate 'PT404' using message='Post not found'; end if;
    if post.user_id is distinct from p_actor and not exists (select 1 from public.auth_roles where user_id=p_actor and role='admins') then
      raise sqlstate 'PT403' using message='Post belongs to another author';
    end if;
    update public.blog_posts set title=p_title,text=p_text,tags=p_tags where id=p_post_id returning * into post;
  else
    identifier:=nextval('public.blog_post_id_seq');
    permalink:=trim(both '-' from regexp_replace(lower(p_title),'[^a-z0-9]+','-','g')) || '-' || identifier::text;
    insert into public.blog_posts (id,doc_id,user_id,title,text,tags,score,ts,permalink)
    values (identifier,identifier::text,p_actor,p_title,p_text,p_tags,0,(extract(epoch from now())*1000)::bigint,permalink)
    returning * into post;
  end if;
  return jsonb_build_object('id',post.id,'permalink',post.permalink);
end $$;
revoke all on function public.tolgraven_save_post(text,bigint,text,text,text) from public,anon,authenticated;
grant execute on function public.tolgraven_save_post(text,bigint,text,text,text) to service_role;

-- Uploads use the authenticated server endpoint. The public bucket allows image
-- reads, while Storage's default RLS denies client uploads and overwrites.
do $$ begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
      values ('avatars','avatars',true,20971520,array['image/png','image/webp','image/avif'])
      on conflict (id) do update
        set allowed_mime_types = excluded.allowed_mime_types;
  end if;
end $$;

-- Search uses the same public post/comment fields as the live store. Prefix
-- matching keeps instant search useful while the GIN indexes avoid table scans.
create index if not exists blog_posts_search_idx on public.blog_posts using gin
  (to_tsvector('simple',coalesce(title,'') || ' ' || coalesce(text,'') || ' ' || coalesce(tags,'')));
create index if not exists blog_comments_search_idx on public.blog_comments using gin
  (to_tsvector('simple',coalesce(title,'') || ' ' || coalesce(text,'')));
create or replace function public.tolgraven_search(p_collection text,p_query text,p_page integer,p_per_page integer)
returns jsonb language plpgsql security invoker set search_path='' as $$
declare query tsquery; result jsonb;
begin
  if p_collection not in ('blog-posts','blog-comments') or p_query is null
     or length(p_query)>200 or p_page not between 1 and 10000 or p_per_page not between 1 and 100 then
    raise sqlstate 'PT400' using message='Invalid search';
  end if;
  query:=to_tsquery('simple',(select string_agg(quote_literal(token) || ':*',' & ')
                            from regexp_split_to_table(p_query,'[^[:alnum:]_]+') token where token<>''));
  with documents as (
    select jsonb_build_object('id',id,'permalink',permalink,'title',title,'text',text,'tags',tags,'user',user_id,'ts',ts) as document,
           text, to_tsvector('simple',coalesce(title,'') || ' ' || coalesce(text,'') || ' ' || coalesce(tags,'')) as vector
    from public.blog_posts where p_collection='blog-posts'
    union all
    select jsonb_build_object('id',id,'title',title,'text',text,'user',user_id,'ts',ts,'path',path,'parent-post',parent_post),
           text, to_tsvector('simple',coalesce(title,'') || ' ' || coalesce(text,''))
    from public.blog_comments where p_collection='blog-comments'
  ), matched as (
    select document,text,ts_rank(vector,query) as rank from documents where vector @@ query
  ), page as (
    select jsonb_build_object('document',document,'text_match',rank,'highlights',
             jsonb_build_array(jsonb_build_object('field','text','snippet',
               ts_headline('simple',coalesce(text,''),query,'StartSel=<mark>, StopSel=</mark>, MaxWords=45, MinWords=15')))) as hit
    from matched order by rank desc,document->>'id' limit p_per_page offset (p_page-1)*p_per_page
  ) select jsonb_build_object('found',(select count(*) from matched),'hits',coalesce(jsonb_agg(hit),'[]'::jsonb),
                              'page',p_page,'request_params',jsonb_build_object('q',p_query,'collection_name',p_collection))
    into result from page;
  return result;
end $$;
revoke all on function public.tolgraven_search(text,text,integer,integer) from public,anon,authenticated;
grant execute on function public.tolgraven_search(text,text,integer,integer) to service_role;
notify pgrst,'reload schema';
