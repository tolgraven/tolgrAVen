(ns website.supabase.retention
  "Disable public access while retaining all installed feature rows."
  (:require [clojure.string :as string]
            #?(:clj [website.build.modules :refer [retention-definitions]])))

#?(:clj (def disabled (retention-definitions)))

(defn sql [{:keys [tables functions avatars?]}]
  (string/join "\n"
    (concat
      (when avatars?
        ["do $$ begin if to_regclass('storage.buckets') is not null then update storage.buckets set public=false where id='avatars'; end if; end $$;"])
      (for [table tables]
        (str "do $$ declare columns text; begin "
             "if to_regclass('public." table "') is not null then "
             "execute 'revoke all on public." table " from anon,authenticated'; "
             "select string_agg(quote_ident(attname),',') into columns from pg_attribute "
             "where attrelid='public." table "'::regclass and attnum>0 and not attisdropped; "
             "execute 'revoke all (' || columns || ') on public." table " from anon,authenticated'; "
             "if exists(select 1 from pg_publication_tables where pubname='supabase_realtime' "
             "and schemaname='public' and tablename='" table "') then "
             "alter publication supabase_realtime drop table public." table "; end if; "
             "end if; end $$;"))
      (for [function functions]
        (str "do $$ declare routine regprocedure; begin "
             "for routine in select p.oid::regprocedure from pg_proc p join pg_namespace n on n.oid=p.pronamespace "
             "where n.nspname='public' and p.proname='" function "' loop "
             "execute 'revoke all on function ' || routine || ' from public,anon,authenticated,service_role'; "
             "end loop; end $$;")))))
