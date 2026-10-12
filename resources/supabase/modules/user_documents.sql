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
  begin
    alter publication supabase_realtime add table public.user_documents;
  exception when duplicate_object then null;
  end;
end $$;

do $$ begin
  if to_regclass('public.store_documents') is not null then
-- Only explicitly owned legacy threads are transferred; shared or anonymous
-- documents are kept private in the archive rather than assigned to a caller.
insert into public.user_documents (owner_id, collection, doc_id, data)
select d.data->>'user', d.collection, d.doc_id, d.data
from public.store_documents d join public.site_users u on u.id=d.data->>'user'
where d.collection='gpt-threads'
on conflict do nothing;
  end if;
end $$;

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
