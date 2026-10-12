-- Uploads use the authenticated server endpoint. The public bucket allows image
-- reads, while Storage's default RLS denies client uploads and overwrites.
do $$ begin
  if to_regclass('storage.buckets') is not null then
    insert into storage.buckets (id,name,public,file_size_limit,allowed_mime_types)
      values ('avatars','avatars',true,20971520,array['image/png','image/webp','image/avif'])
      on conflict (id) do update
        set public = true, allowed_mime_types = excluded.allowed_mime_types;
  end if;
end $$;
