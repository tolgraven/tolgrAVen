select public.website_search_register('fixture_v1',array['blog-post','blog-comment','github-commit']);
insert into public.blog_posts values(42,'Original','Public text',123,'writer','site-news');

do $$
declare job record; newer record; amount integer;
begin
  select * into job from public.website_search_claim('fixture_v1','first',40);
  if job.document->>'title'<>'Original' or job.revision<>1 then raise exception 'Initial projection missing'; end if;
  update public.blog_posts set title='Edited' where id=42;
  select count(*) into amount from public.website_search_claim('fixture_v1','second',40);
  if amount<>0 then raise exception 'Concurrent worker stole an active delivery'; end if;
  -- Simulate a suspended process whose lease expires, then another successful worker.
  update public.website_search_jobs set lease_until=now()-interval '1 second';
  select * into newer from public.website_search_claim('fixture_v1','second',40);
  if newer.document->>'title'<>'Edited' or newer.revision<>2 then raise exception 'New revision missing'; end if;
  perform public.website_search_finish('fixture_v1','second',newer.id,newer.revision,true);
  if exists(select 1 from public.website_search_jobs) then raise exception 'Success did not acknowledge the revision'; end if;
  -- Old Typesense delivery after newer ACK must re-enqueue the current revision.
  perform public.website_search_finish('fixture_v1','first',job.id,job.revision,true);
  select * into newer from public.website_search_claim('fixture_v1','repair',40);
  if newer.revision<>2 then raise exception 'Stale lease did not schedule reconciliation'; end if;
  perform public.website_search_finish('fixture_v1','repair',newer.id,newer.revision,true);
end $$;

insert into public.blog_comments values('113','Reply','Comment body',124,'writer',42);
do $$ begin
  if (select document->>'href' from public.website_search_documents where id='blog-comment-113')<>'/blog/post/42/comment/113' then
    raise exception 'Comment navigation missing';
  end if;
end $$;
delete from public.blog_comments where id='113';
do $$ begin
  if (select document is not null from public.website_search_documents where id='blog-comment-113') then
    raise exception 'Comment delete did not write a tombstone';
  end if;
end $$;

insert into public.github_repositories values('public-owner','repository',true);
insert into public.github_commits values('public-owner','repository',repeat('a',40),'search: fixture',now(),'Author','author',now());
do $$ begin
  if not exists(select 1 from public.website_search_documents where kind='github-commit' and document is not null) then
    raise exception 'Public commit was not indexed';
  end if;
end $$;
update public.github_repositories set is_public=false;
do $$ begin
  if exists(select 1 from public.website_search_documents where kind='github-commit' and document is not null) then
    raise exception 'Repository visibility failed to revoke index documents';
  end if;
  if has_function_privilege('anon','public.website_search_claim(text,text,integer)','execute')
     or has_function_privilege('authenticated','public.website_search_put(text,text,jsonb)','execute')
     or has_table_privilege('anon','public.website_search_documents','select') then
    raise exception 'Private index boundary exposed';
  end if;
end $$;

select public.website_search_register('another_site',array['blog-post']);
do $$ begin
  if (select count(*) from public.website_search_claim('another_site','independent',40))<>1 then
    raise exception 'Independent consumer cannot backfill';
  end if;
end $$;
select 'Search queue publication, edits, deletes, leases, reconciliation, visibility and private permissions passed' as result;
