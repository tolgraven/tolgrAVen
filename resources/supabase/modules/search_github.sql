create or replace function public.website_search_project_github(p_row public.github_commits,p_deleted boolean default false)
returns void language plpgsql security definer set search_path='' as $$
declare doc jsonb; v_id text;
begin
  -- Repository names can contain hyphens; hash the unambiguous owner/repo key.
  v_id:='github-commit-'||md5(p_row.owner||'/'||p_row.repo)||'-'||p_row.sha;
  if not p_deleted and exists(select 1 from public.github_repositories where owner=p_row.owner and repo=p_row.repo and is_public) then
    doc:=jsonb_build_object('id',v_id,'kind','github-commit','title',split_part(p_row.message,E'\n',1),
      'text',p_row.message,'href','/github/commits/'||p_row.sha,
      'repository',p_row.owner||'/'||p_row.repo,'ts',floor(extract(epoch from p_row.committed_at)*1000)::bigint,
      'author',coalesce(p_row.author_name,p_row.author_login,''));
  end if;
  perform public.website_search_put(v_id,'github-commit',doc);
end $$;

create or replace function public.website_search_github_commit()
returns trigger language plpgsql security definer set search_path='' as $$
begin
  if TG_OP='DELETE' then perform public.website_search_project_github(old,true);
  else perform public.website_search_project_github(new); end if;
  return null;
end $$;
drop trigger if exists website_search_github_commit on public.github_commits;
create trigger website_search_github_commit after insert or update or delete on public.github_commits
  for each row execute function public.website_search_github_commit();

-- Revoke or restore public projections without rewriting archive timestamps or
-- emitting unrelated commit-row updates to realtime consumers.
create or replace function public.website_search_github_visibility()
returns trigger language plpgsql security definer set search_path='' as $$
declare row public.github_commits;
begin
  if old.is_public is distinct from new.is_public then
    for row in select * from public.github_commits where owner=new.owner and repo=new.repo loop
      perform public.website_search_project_github(row);
    end loop;
  end if;
  return null;
end $$;
drop trigger if exists website_search_github_visibility on public.github_repositories;
create trigger website_search_github_visibility after update on public.github_repositories
  for each row execute function public.website_search_github_visibility();

do $$ declare row public.github_commits; begin
  for row in select * from public.github_commits loop
    perform public.website_search_project_github(row);
  end loop;
end $$;
revoke all on function public.website_search_project_github(public.github_commits,boolean),
  public.website_search_github_commit(),public.website_search_github_visibility() from public,anon,authenticated;
