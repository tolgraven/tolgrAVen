create or replace function public.website_search_blog_post()
returns trigger language plpgsql security definer set search_path='' as $$
declare row public.blog_posts; doc jsonb;
begin
  if TG_OP='DELETE' then row:=old; else row:=new; end if;
  if TG_OP<>'DELETE' then
    doc:=jsonb_build_object('id','blog-post-'||row.id,'kind','blog-post','title',row.title,'text',row.text,
      'href','/blog/post/'||row.id,'ts',row.ts,'user',row.user_id,'tags',coalesce(row.tags,''));
  end if;
  perform public.website_search_put('blog-post-'||row.id,'blog-post',doc);
  return null;
end $$;
drop trigger if exists website_search_blog_post on public.blog_posts;
create trigger website_search_blog_post after insert or update or delete on public.blog_posts
  for each row execute function public.website_search_blog_post();
select public.website_search_put('blog-post-'||id,'blog-post',jsonb_build_object(
  'id','blog-post-'||id,'kind','blog-post','title',title,'text',text,'href','/blog/post/'||id,
  'ts',ts,'user',user_id,'tags',coalesce(tags,''))) from public.blog_posts;
revoke all on function public.website_search_blog_post() from public,anon,authenticated;
