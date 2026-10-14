-- Durable public projections and independent delivery checkpoints per site/index.
-- Triggers run in the same transaction as publication, edits and deletion.
create table if not exists public.website_search_documents (
  id text primary key,
  kind text not null,
  document jsonb,
  revision bigint not null default 1,
  updated_at timestamptz not null default now()
);
create table if not exists public.website_search_consumers (
  id text primary key,
  kinds text[] not null
);
create table if not exists public.website_search_jobs (
  consumer text references public.website_search_consumers(id) on delete cascade,
  id text references public.website_search_documents(id) on delete cascade,
  revision bigint not null,
  worker text,
  lease_until timestamptz,
  retry_at timestamptz not null default now(),
  attempts integer not null default 0,
  primary key (consumer,id)
);
alter table public.website_search_documents enable row level security;
alter table public.website_search_consumers enable row level security;
alter table public.website_search_jobs enable row level security;
revoke all on public.website_search_documents, public.website_search_consumers, public.website_search_jobs from public,anon,authenticated;
grant all on public.website_search_documents, public.website_search_consumers, public.website_search_jobs to service_role;

create or replace function public.website_search_put(p_id text,p_kind text,p_document jsonb)
returns void language plpgsql security definer set search_path='' as $$
declare v_revision bigint;
begin
  insert into public.website_search_documents(id,kind,document)
    values(p_id,p_kind,p_document)
    on conflict(id) do update set document=excluded.document,kind=excluded.kind,
      revision=website_search_documents.revision+1,updated_at=now()
      where website_search_documents.document is distinct from excluded.document
    returning revision into v_revision;
  if v_revision is null then return; end if;
  insert into public.website_search_jobs(consumer,id,revision)
    select id,p_id,v_revision from public.website_search_consumers where p_kind=any(kinds)
    on conflict(consumer,id) do update set revision=excluded.revision,retry_at=now(),attempts=0;
end $$;

create or replace function public.website_search_register(p_consumer text,p_kinds text[])
returns void language plpgsql security invoker set search_path='' as $$
begin
  if p_consumer !~ '^[a-zA-Z0-9_-]{1,80}$' or cardinality(p_kinds)>40 then
    raise sqlstate 'PT400' using message='Invalid search consumer';
  end if;
  insert into public.website_search_consumers(id,kinds) values(p_consumer,p_kinds)
    on conflict(id) do update set kinds=excluded.kinds;
  delete from public.website_search_jobs j using public.website_search_documents d
    where j.consumer=p_consumer and j.id=d.id and not(d.kind=any(p_kinds));
  -- Existing tombstones must also reach a newly registered consumer.
  insert into public.website_search_jobs(consumer,id,revision)
    select p_consumer,id,revision from public.website_search_documents where kind=any(p_kinds)
    on conflict(consumer,id) do nothing;
end $$;

create or replace function public.website_search_claim(p_consumer text,p_worker text,p_limit integer)
returns table(id text,kind text,document jsonb,revision bigint)
language plpgsql security invoker set search_path='' as $$
begin
  if p_limit not between 1 and 100 then raise sqlstate 'PT400' using message='Invalid search batch'; end if;
  return query with candidates as (
    select j.id from public.website_search_jobs j
    where j.consumer=p_consumer and j.retry_at<=now() and (j.lease_until is null or j.lease_until<now())
    order by j.retry_at,j.id for update skip locked limit p_limit
  ), claimed as (
    update public.website_search_jobs j set worker=p_worker,lease_until=now()+interval '5 minutes',attempts=attempts+1
    from candidates c where j.consumer=p_consumer and j.id=c.id returning j.id,j.revision
  ) select d.id,d.kind,d.document,d.revision from claimed c join public.website_search_documents d on d.id=c.id;
end $$;

create or replace function public.website_search_finish(p_consumer text,p_worker text,p_id text,p_revision bigint,p_success boolean)
returns void language plpgsql security invoker set search_path='' as $$
begin
  if p_success then
    delete from public.website_search_jobs where consumer=p_consumer and id=p_id
      and worker=p_worker and revision=p_revision;
    -- A paused worker may resume after its lease expired and deliver an older
    -- revision after another worker finished. Always schedule the current value
    -- again in that case, even if the newer job has already been acknowledged.
    insert into public.website_search_jobs(consumer,id,revision)
      select p_consumer,d.id,d.revision from public.website_search_documents d
      join public.website_search_consumers c on c.id=p_consumer
      where d.id=p_id and d.revision<>p_revision and d.kind=any(c.kinds)
      on conflict(consumer,id) do update set revision=excluded.revision,retry_at=now();
  end if;
  -- A newer edit remains pending; failed deliveries retry with bounded backoff.
  update public.website_search_jobs set worker=null,lease_until=null,
    retry_at=case when revision<>p_revision then now()
                  else now()+make_interval(secs=>least(300,5*attempts)) end
    where consumer=p_consumer and id=p_id and worker=p_worker;
end $$;

-- Idempotent operator reconciliation after an index restore or lost collection.
create or replace function public.website_search_replay(p_consumer text)
returns void language sql security invoker set search_path='' as $$
  insert into public.website_search_jobs(consumer,id,revision)
    select p_consumer,d.id,d.revision from public.website_search_documents d
    join public.website_search_consumers c on c.id=p_consumer where d.kind=any(c.kinds)
    on conflict(consumer,id) do update set revision=excluded.revision,retry_at=now();
$$;
revoke all on function public.website_search_put(text,text,jsonb),public.website_search_register(text,text[]),
  public.website_search_claim(text,text,integer),public.website_search_finish(text,text,text,bigint,boolean),
  public.website_search_replay(text) from public,anon,authenticated;
grant execute on function public.website_search_put(text,text,jsonb),public.website_search_register(text,text[]),
  public.website_search_claim(text,text,integer),public.website_search_finish(text,text,text,bigint,boolean),
  public.website_search_replay(text) to service_role;
