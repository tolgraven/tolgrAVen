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
