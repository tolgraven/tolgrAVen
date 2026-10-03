-- Run with psql -v ON_ERROR_STOP=1 -f this-file on a bootstrapped database.
-- All fixtures, test helpers and changes roll back, including on assertion failure.
begin;
create function pg_temp.assert_true(condition boolean, message text)
returns void language plpgsql as $$
begin
  if condition is distinct from true then raise exception 'Assertion failed: %', message; end if;
end $$;

insert into public.site_users(id, comment_count, karma, comments)
  values ('migration-test-author', 4, 7, '["old"]'),
         ('migration-test-voter', 0, 0, '[]');
insert into public.blog_posts(doc_id, id, user_id, title, text, ts)
  values ('migration-test-post', 9223372036854775000, 'migration-test-author', 'fixture', 'fixture', 1),
         ('migration-test-other-post', 9223372036854775001, 'migration-test-author', 'fixture', 'fixture', 1);

set local role service_role;
do $$
declare
  v_comment text;
  v_reply text;
  v_legacy text;
  v_chat jsonb;
  v_result jsonb;
  v_count bigint;
begin
  v_chat := public.tolgraven_post_chat('migration-test-voter', 'message');
  perform pg_temp.assert_true((v_chat->>'user') = 'migration-test-voter', 'chat author');
  perform pg_temp.assert_true((v_chat->>'time')::bigint > 1, 'server timestamp');
  perform pg_temp.assert_true(exists(select 1 from public.chat_messages where message_id = v_chat->>'id'), 'chat persisted');
  v_result := public.tolgraven_create_comment('migration-test-author', 9223372036854775000, null, 'comment', null);
  v_comment := v_result->>'id';
  v_reply := public.tolgraven_create_comment('migration-test-author', 9223372036854775000, v_comment, 'reply', 'title')->>'id';
  perform pg_temp.assert_true((select path = jsonb_build_array(9223372036854775000::bigint, v_comment) from public.blog_comments where id = v_reply), 'canonical reply path');
  perform pg_temp.assert_true((select comment_count = 6 and comments ? v_comment and comments ? v_reply from public.site_users where id = 'migration-test-author'), 'counter and comment list updated together');
  perform public.tolgraven_edit_comment('migration-test-author', v_comment, 'edited', 'title');
  perform pg_temp.assert_true((select text = 'edited' and user_id = 'migration-test-author' and score = 0 from public.blog_comments where id = v_comment), 'edit preserves author and score');
  begin
    perform public.tolgraven_edit_comment('migration-test-voter', v_comment, 'stolen', null);
    raise exception 'Edit unexpectedly allowed';
  exception when sqlstate 'PT403' then null;
  end;
  begin
    perform public.tolgraven_create_comment('migration-test-voter', 9223372036854775001, v_comment, 'invalid reply', null);
    raise exception 'Cross-post reply unexpectedly allowed';
  exception when sqlstate 'PT400' then null;
  end;
  begin
    perform public.tolgraven_create_comment('migration-test-new-user', 9223372036854775002, null, 'missing post', null);
    raise exception 'Missing post unexpectedly allowed';
  exception when sqlstate 'PT404' then null;
  end;
  perform pg_temp.assert_true(not exists(select 1 from public.site_users where id = 'migration-test-new-user'), 'rejected write creates no profile');
  begin
    perform public.tolgraven_set_comment_vote('migration-test-author', v_comment, 1::smallint, 0::smallint);
    raise exception 'Self vote unexpectedly allowed';
  exception when sqlstate 'PT403' then null;
  end;
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_comment, 1::smallint, 0::smallint);
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_comment, 1::smallint, 0::smallint);
  perform pg_temp.assert_true((select score = 1 from public.blog_comments where id = v_comment), 'repeat vote is idempotent');
  perform pg_temp.assert_true((select karma = 8 from public.site_users where id = 'migration-test-author'), 'repeat vote updates karma once');
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_comment, -1::smallint, 1::smallint);
  perform pg_temp.assert_true((select score = -1 from public.blog_comments where id = v_comment), 'opposite vote delta');
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_comment, 0::smallint, 1::smallint);
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_comment, 0::smallint, 1::smallint);
  perform pg_temp.assert_true((select score = 0 from public.blog_comments where id = v_comment), 'removal and repeat removal');
  perform pg_temp.assert_true((select karma = 7 from public.site_users where id = 'migration-test-author'), 'karma baseline restored');
  perform pg_temp.assert_true((select vote = 0 from public.comment_votes where user_id = 'migration-test-voter' and comment_id = v_comment), 'zero vote retains consumed baseline');
  v_legacy := public.tolgraven_create_comment('migration-test-author', 9223372036854775000, null, 'legacy fixture', null)->>'id';
  update public.blog_comments set score = 5 where id = v_legacy;
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_legacy, 1::smallint, 1::smallint);
  perform pg_temp.assert_true((select score = 5 from public.blog_comments where id = v_legacy), 'legacy repeated vote preserves imported score');
  perform public.tolgraven_set_comment_vote('migration-test-voter', v_legacy, 0::smallint, 1::smallint);
  perform pg_temp.assert_true((select score = 4 from public.blog_comments where id = v_legacy), 'legacy vote can be removed exactly once');
end $$;
reset role;

-- Inject a failure after comment insertion to verify transaction rollback also
-- removes the generated comment and newly created actor profile.
create function pg_temp.reject_fixture_update() returns trigger language plpgsql as $$
begin
  if new.id = 'migration-test-reject' then raise check_violation using message = 'fixture failure'; end if;
  return new;
end $$;
create trigger migration_test_reject_update before update on public.site_users
  for each row execute function pg_temp.reject_fixture_update();
set local role service_role;
do $$
begin
  begin
    perform public.tolgraven_create_comment('migration-test-reject', 9223372036854775000, null, 'must roll back', null);
    raise exception 'Failure injection unexpectedly succeeded';
  exception when check_violation then null;
  end;
  perform pg_temp.assert_true(not exists(select 1 from public.site_users where id = 'migration-test-reject'), 'failed transaction rolls back profile');
  perform pg_temp.assert_true(not exists(select 1 from public.blog_comments where user_id = 'migration-test-reject'), 'failed transaction rolls back comment');
end $$;
reset role;

set local role anon;
do $$
begin
  begin
    perform public.tolgraven_post_chat('migration-test-voter', 'forged');
    raise exception 'Anonymous RPC unexpectedly allowed';
  exception when insufficient_privilege then null;
  end;
  begin
    perform user_id from public.comment_votes;
    raise exception 'Anonymous vote history unexpectedly readable';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
do $$
begin
  perform pg_temp.assert_true(not has_function_privilege('authenticated', 'public.tolgraven_post_chat(text,text)', 'execute'), 'authenticated role cannot spoof actor');
  perform pg_temp.assert_true(not has_function_privilege('anon', 'public.tolgraven_create_comment(text,bigint,text,text,text)', 'execute'), 'anonymous comments denied');
  perform pg_temp.assert_true(not has_function_privilege('authenticated', 'public.tolgraven_set_comment_vote(text,text,smallint,smallint)', 'execute'), 'direct votes denied');
  perform pg_temp.assert_true(not has_function_privilege('authenticated', 'public.tolgraven_edit_comment(text,text,text,text)', 'execute'), 'direct edits denied');
  perform pg_temp.assert_true((select bool_and(not prosecdef) from pg_proc where proname in ('tolgraven_post_chat','tolgraven_create_comment','tolgraven_edit_comment','tolgraven_set_comment_vote')), 'RPCs use invoker permissions');
  perform pg_temp.assert_true((select relrowsecurity from pg_class where oid = 'public.comment_votes'::regclass), 'ledger has RLS');
end $$;
select 'chat, comments, ownership, vote deltas, rollback and permissions passed' as verification;
rollback;
