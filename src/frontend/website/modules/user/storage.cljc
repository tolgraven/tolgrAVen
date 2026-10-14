(ns website.modules.user.storage
  "Seed persistence owned by the user module; portable declaration data.")

(def tables
  [{:table :site_users
    :sql "supabase/modules/site_users.sql"
    :seed-key :users
    :collections #{"users"}
    :queries {"users" {:document-key :id
                       :fields {:id :id, :name :name}
                       :batch-field :id}}
    :key [:id]
    :order [[:id :asc]]
    :jsonb #{:comments :voted :raw}
    :public-columns "id,seq_id,name,avatar,bg_color,comment_count,karma"}
   {:table :auth_roles
    :sql "supabase/modules/auth_roles.sql"
    :seed-key :roles
    :key [:role :user_id]
    :order [[:role :asc] [:user_id :asc]]
    :select [:role :user_id]
    :references #{:site_users}}])
