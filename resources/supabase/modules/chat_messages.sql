create table if not exists chat_messages (
  message_id text primary key,
  ts bigint not null,
  user_id text,
  text text,
  raw jsonb not null default '{}'::jsonb
);

alter table chat_messages enable row level security;

revoke all on chat_messages from anon, authenticated;

grant select (message_id, ts, user_id, text) on chat_messages to anon, authenticated;

grant all on chat_messages to service_role;

drop policy if exists "Public read chat_messages" on chat_messages;

create policy "Public read chat_messages"
  on chat_messages for select
  using (true);

alter table chat_messages replica identity full;

do $$ begin
  begin
    alter publication supabase_realtime add table public.chat_messages;
  exception when duplicate_object then null;
  end;
end $$;

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

revoke all on function public.tolgraven_post_chat(text, text) from public, anon, authenticated;

grant execute on function public.tolgraven_post_chat(text, text) to service_role;
