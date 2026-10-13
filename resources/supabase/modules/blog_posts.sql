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

alter table blog_posts add column if not exists image_assets jsonb not null default '{}'::jsonb;

alter table blog_posts enable row level security;

revoke all on blog_posts from anon, authenticated;

grant select (doc_id, id, permalink, user_id, title, text, tags, score, ts, image_assets)
  on blog_posts to anon, authenticated;

grant all on blog_posts to service_role;

drop policy if exists "Public read blog_posts" on blog_posts;

create policy "Public read blog_posts"
  on blog_posts for select
  using (true);

alter table blog_posts replica identity full;

do $$ begin
  begin
    alter publication supabase_realtime add table public.blog_posts;
  exception when duplicate_object then null;
  end;
end $$;

create sequence if not exists public.blog_post_id_seq as bigint;

-- Applying this script is transactional; prevent a concurrent publisher from
-- taking a sequence value while its imported baseline is established.
lock table public.blog_posts in share row exclusive mode;

select setval('public.blog_post_id_seq', greatest(coalesce((select max(id) from public.blog_posts),0),
                                                 (select last_value from public.blog_post_id_seq),1));

grant usage,select on sequence public.blog_post_id_seq to service_role;

drop function if exists public.tolgraven_save_post(text,bigint,text,text,text);

create or replace function public.tolgraven_save_post(
  p_actor text, p_post_id bigint, p_title text, p_text text, p_tags text, p_image_assets jsonb default '{}'::jsonb
) returns jsonb language plpgsql security invoker set search_path='' as $$
declare post public.blog_posts; identifier bigint; permalink text;
begin
  if not exists (select 1 from public.auth_roles where user_id=p_actor and role in ('admins','bloggers')) then
    raise sqlstate 'PT403' using message='Publishing role required';
  end if;
  if p_title is null or length(btrim(p_title))=0 or length(p_title)>200
     or p_text is null or length(btrim(p_text))=0 or length(p_text)>200000
     or length(p_tags)>2000 or jsonb_typeof(p_image_assets) <> 'object' then
    raise sqlstate 'PT400' using message='Invalid post';
  end if;
  if p_post_id is not null then
    select * into post from public.blog_posts where id=p_post_id for update;
    if not found then raise sqlstate 'PT404' using message='Post not found'; end if;
    if post.user_id is distinct from p_actor and not exists (select 1 from public.auth_roles where user_id=p_actor and role='admins') then
      raise sqlstate 'PT403' using message='Post belongs to another author';
    end if;
    update public.blog_posts set title=p_title,text=p_text,tags=p_tags,image_assets=p_image_assets where id=p_post_id returning * into post;
  else
    identifier:=nextval('public.blog_post_id_seq');
    permalink:=trim(both '-' from regexp_replace(lower(p_title),'[^a-z0-9]+','-','g')) || '-' || identifier::text;
    insert into public.blog_posts (id,doc_id,user_id,title,text,tags,score,ts,permalink,image_assets)
    values (identifier,identifier::text,p_actor,p_title,p_text,p_tags,0,(extract(epoch from now())*1000)::bigint,permalink,p_image_assets)
    returning * into post;
  end if;
  return jsonb_build_object('id',post.id,'permalink',post.permalink);
end $$;

revoke all on function public.tolgraven_save_post(text,bigint,text,text,text,jsonb) from public,anon,authenticated;

grant execute on function public.tolgraven_save_post(text,bigint,text,text,text,jsonb) to service_role;
