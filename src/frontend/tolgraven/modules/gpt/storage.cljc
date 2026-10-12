(ns tolgraven.modules.gpt.storage
  "Private document persistence owned by GPT; excluded from legacy seed imports.")

(def tables
  [{:table :user_documents
    :sql "supabase/modules/user_documents.sql"
    :cache-key :store_documents
    :user-collections #{"gpt" "gpt-threads"}
    :key [:owner_id :collection :doc_id]
    :order [[:owner_id :asc] [:collection :asc] [:doc_id :asc]]
    :jsonb #{:data}
    :references #{:site_users}
    :public-columns "owner_id,collection,doc_id,data,updated_at"}])
