-- New fixtures and assertions roll back. Sequence gaps are expected.
begin;
create function pg_temp.assert_true(condition boolean,message text) returns void language plpgsql as $$
begin if condition is distinct from true then raise exception 'Assertion failed: %',message; end if; end $$;
insert into public.site_users(id,name) values ('cutover-author','Fixture'),('cutover-other','Other'),('cutover-admin','Admin');
insert into public.auth_roles(role,user_id) values ('bloggers','cutover-author'),('bloggers','cutover-other'),('admins','cutover-admin');
set local role service_role;
do $$
declare post jsonb; other jsonb; document jsonb; search jsonb; identifier bigint; link text;
begin
  post:=public.tolgraven_save_post('cutover-author',null,'Cutover search fixture','Supabase streamedword cutoversearchfixture','fixture');
  other:=public.tolgraven_save_post('cutover-other',null,'Other fixture','Other contents','fixture');
  identifier:=(post->>'id')::bigint; link:=post->>'permalink';
  perform pg_temp.assert_true(identifier<>(other->>'id')::bigint,'publisher IDs are distinct');
  perform pg_temp.assert_true((select user_id='cutover-author' and ts>1 and score=0 from public.blog_posts where id=identifier),'post metadata comes from server');
  perform public.tolgraven_save_post('cutover-author',identifier,'Edited title','Supabase streamedword cutoversearchfixture','edited');
  perform pg_temp.assert_true((select permalink=link and user_id='cutover-author' from public.blog_posts where id=identifier),'edits preserve permalink and ownership');
  begin perform public.tolgraven_save_post('cutover-other',identifier,'Stolen','Stolen','fixture'); raise exception 'Ownership bypass'; exception when sqlstate 'PT403' then null; end;
  begin perform public.tolgraven_save_post('unprivileged',null,'Bad','Bad','fixture'); raise exception 'Role bypass'; exception when sqlstate 'PT403' then null; end;
  update public.blog_posts set user_id=null where id=identifier;
  begin perform public.tolgraven_save_post('cutover-other',identifier,'Orphan','Orphan','fixture'); raise exception 'Null ownership bypass'; exception when sqlstate 'PT403' then null; end;
  perform public.tolgraven_save_post('cutover-admin',identifier,'Admin edit','Supabase streamedword cutoversearchfixture','fixture');
  search:=public.tolgraven_search('blog-posts','cutoversearchfi',1,15);
  perform pg_temp.assert_true((search->>'found')::bigint=1,'prefix search uses native data');
  perform pg_temp.assert_true(search->'hits'->0->'document'->>'title'='Admin edit','search sees latest edits');
  perform pg_temp.assert_true(not (search->'hits'->0->'document' ? 'raw'),'search omits import data');
  perform public.tolgraven_create_comment('cutover-author',identifier,null,'Native comment search cutovercommentfixture',null);
  search:=public.tolgraven_search('blog-comments','cutovercommentfi',1,15);
  perform pg_temp.assert_true((search->>'found')::bigint=1,'comment search works');
  perform pg_temp.assert_true((public.tolgraven_search('blog-posts','nonexistentfixture',1,15)->'hits')='[]'::jsonb,'empty search results');
  document:=public.tolgraven_save_document('cutover-author','gpt-threads','same-id','{"messages":["secret"],"user":"victim","time":1}',false);
  perform pg_temp.assert_true(document->>'user'='cutover-author','document owner is authoritative');
  document:=public.tolgraven_save_document('cutover-author','gpt-threads','same-id','{"messages":["new"]}',true);
  perform pg_temp.assert_true(document->>'time'='1' and document->'messages'='["new"]'::jsonb,'document merge preserves other fields');
  perform public.tolgraven_save_document('cutover-other','gpt-threads','same-id','{"messages":["other"]}',false);
  begin perform public.tolgraven_save_document('cutover-author','secrets','auth','{}',false); raise exception 'Private configuration write bypass'; exception when sqlstate 'PT400' then null; end;
end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","app_metadata":{"site_user_id":"cutover-author"}}',true);
set local role authenticated;
select pg_temp.assert_true((select count(*)=1 and bool_and(owner_id='cutover-author') from public.user_documents),'RLS returns only owned documents');
do $$ begin
  begin insert into public.user_documents values ('cutover-other','gpt-threads','stolen','{}',now()); raise exception 'Direct write bypass'; exception when insufficient_privilege then null; end;
  begin perform public.tolgraven_save_post('cutover-admin',null,'Forged','Forged',null); raise exception 'RPC bypass'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select set_config('request.jwt.claims','{"sub":"11111111-1111-4111-8111-111111111111","role":"authenticated","user_metadata":{"site_user_id":"cutover-author"}}',true);
set local role authenticated;
select pg_temp.assert_true((select count(*)=0 from public.user_documents),'user-editable linkage is ignored');
reset role;
set local role anon;
do $$ begin
  begin perform data from public.user_documents; raise exception 'Anonymous private reads'; exception when insufficient_privilege then null; end;
end $$;
reset role;
select pg_temp.assert_true(not has_function_privilege('authenticated','public.tolgraven_save_document(text,text,text,jsonb,boolean)','execute'),'direct private RPC cannot spoof owner');
select 'publishing, native search, private documents and RLS passed' as verification;
rollback;
