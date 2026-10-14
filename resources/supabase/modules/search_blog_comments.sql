create or replace function public.website_search_blog_comment()
returns trigger language plpgsql security definer set search_path='' as $$
declare row public.blog_comments; doc jsonb;
begin
  if TG_OP='DELETE' then row:=old; else row:=new; end if;
  if TG_OP<>'DELETE' then
    doc:=jsonb_build_object('id','blog-comment-'||row.id,'kind','blog-comment','title',coalesce(nullif(row.title,''),'Comment'),
      'text',row.text,'href','/blog/post/'||row.parent_post||'/comment/'||row.id,
      'thread_href','/blog/comment/'||row.id,'ts',row.ts,'user',row.user_id);
  end if;
  perform public.website_search_put('blog-comment-'||row.id,'blog-comment',doc);
  return null;
end $$;
drop trigger if exists website_search_blog_comment on public.blog_comments;
create trigger website_search_blog_comment after insert or update or delete on public.blog_comments
  for each row execute function public.website_search_blog_comment();
select public.website_search_put('blog-comment-'||id,'blog-comment',jsonb_build_object(
  'id','blog-comment-'||id,'kind','blog-comment','title',coalesce(nullif(title,''),'Comment'),'text',text,
  'href','/blog/post/'||parent_post||'/comment/'||id,'thread_href','/blog/comment/'||id,'ts',ts,'user',user_id))
  from public.blog_comments;
revoke all on function public.website_search_blog_comment() from public,anon,authenticated;
