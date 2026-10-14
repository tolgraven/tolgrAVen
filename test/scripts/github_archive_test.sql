\set ON_ERROR_STOP on
create role anon;
create role authenticated;
create role service_role bypassrls;
grant usage on schema public to anon,authenticated,service_role;
\i resources/supabase/modules/github_repositories.sql
\i resources/supabase/modules/github_commits.sql
set role service_role;
select * from public.website_claim_github('test','repo','00000000-0000-0000-0000-000000000001');
do $$ begin
  if exists(select 1 from public.website_claim_github('test','repo','00000000-0000-0000-0000-000000000002')) then
    raise exception 'Concurrent claim was allowed';
  end if;
end $$;
select public.website_finish_github('test','repo','00000000-0000-0000-0000-000000000001','{"stage":"metadata"}',
 '[{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"scss: theme: ancient searchable body","authored_at":"2005-01-01T00:00:00Z","committed_at":"2005-01-01T00:00:00Z","html_url":"https://github.com/test/repo/commit/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","category":"scss","subcategory":"theme"}]',true,true,0);
do $$ declare result jsonb; begin
  result:=public.website_search_github('test','repo','ancient searchable');
  if (result->>'total')::int<>1 or result->'commits'->0->>'message'<>'scss: theme: ancient searchable body' then
    raise exception 'Search did not include old complete body';
  end if;
  if public.website_finish_github('test','repo','00000000-0000-0000-0000-000000000001','{}','[]',true,true,0) then
    raise exception 'Released lease accepted another write';
  end if;
end $$;
select * from public.website_claim_github('test','repo','00000000-0000-0000-0000-000000000002');
select public.website_finish_github('test','repo','00000000-0000-0000-0000-000000000002','{"stage":"metadata"}',
 '[{"sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","message":"duplicate retry","authored_at":"2005-01-01T00:00:00Z","committed_at":"2005-01-01T00:00:00Z","html_url":"https://github.com/test/repo/commit/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]',true,true,0);
do $$ begin
  if (select count(*) from public.github_commits)<>1 then raise exception 'Duplicate retry inserted another commit'; end if;
  if (select message from public.github_commits)<>'scss: theme: ancient searchable body' then raise exception 'Immutable commit changed'; end if;
end $$;
-- Insert a newer row between cursor pages; keyset boundaries must keep the
-- original tail reachable with no repeated SHA despite the changing total.
insert into public.github_commits(owner,repo,sha,message,authored_at,committed_at,html_url)
 select 'test','repo',lpad(to_hex(n),40,'0'),'cursor fixture '||n,
   '2020-01-01'::timestamptz+n*interval '1 day','2020-01-01'::timestamptz+n*interval '1 day','https://github.com/test/repo/commit/'||lpad(to_hex(n),40,'0')
 from generate_series(1,65) n;
do $$ declare first_page jsonb; next_page jsonb; cursor_parts text[]; begin
  first_page:=public.website_search_github('test','repo','cursor fixture');
  cursor_parts:=string_to_array(first_page->>'next-cursor','|');
  insert into public.github_commits(owner,repo,sha,message,authored_at,committed_at,html_url)
    values('test','repo',repeat('f',40),'cursor fixture latest','2026-10-14','2026-10-14','https://github.com/test/repo/commit/'||repeat('f',40));
  next_page:=public.website_search_github('test','repo','cursor fixture',cursor_parts[1]::timestamptz,cursor_parts[2]);
  if jsonb_array_length(next_page->'commits')<>30 then raise exception 'Keyset page lost tail'; end if;
  if exists(select 1 from jsonb_array_elements(first_page->'commits') a,
    jsonb_array_elements(next_page->'commits') b where a->>'sha'=b->>'sha') then raise exception 'Keyset repeated SHA'; end if;
end $$;
set role anon;
select owner,repo,sha,message,category,subcategory from public.github_commits;
do $$ begin
  if has_column_privilege(current_user,'public.github_repositories','state','select') then raise exception 'Private checkpoint readable'; end if;
  if has_function_privilege(current_user,'public.website_claim_github(text,text,uuid)','execute') then raise exception 'Public job claim allowed'; end if;
  if has_table_privilege(current_user,'public.github_commits','insert') then raise exception 'Public write allowed'; end if;
end $$;
set role service_role;
update public.github_repositories set is_public=false;
set role anon;
do $$ begin
  if exists(select owner from public.github_commits) then raise exception 'Private repository leaked through RLS'; end if;
end $$;
reset role;
select 'GitHub archive SQL checks passed' as result;
