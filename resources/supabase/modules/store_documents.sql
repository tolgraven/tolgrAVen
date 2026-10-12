create table if not exists store_documents (
  collection text not null,
  doc_id text not null,
  data jsonb not null default '{}'::jsonb,
  primary key (collection, doc_id)
);

alter table store_documents enable row level security;

revoke all on store_documents from anon, authenticated;

grant all on store_documents to service_role;
