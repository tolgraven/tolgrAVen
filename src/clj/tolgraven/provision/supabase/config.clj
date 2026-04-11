(ns tolgraven.provision.supabase.config)

(def schema-resource "supabase/schema.sql")

(def import-scopes
  {"auth" #{"auth"}
   "blog" #{"blog-comments" "blog-post-ids" "blog-posts"}
   "chat" #{"chat"}
   "gpt" #{"gpt" "gpt-threads"}
   "services" #{"imagor" "instagram" "secrets" "strapi" "strava" "typesense"}
   "users" #{"users"}})
