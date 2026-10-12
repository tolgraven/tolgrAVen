(ns tolgraven.modules.main.storage
  "Seed persistence owned by the main module; portable declaration data.")

(def tables
  [{:table :service_configs
    :sql "supabase/modules/service_configs.sql"
    :seed-key :service_configs
    :key [:service]
    :order [[:service :asc]]
    :jsonb #{:config}
    :feature :services}
   {:table :store_documents
    :sql "supabase/modules/store_documents.sql"
    :seed-key :store_documents
    :key [:collection :doc_id]
    :order [[:collection :asc] [:doc_id :asc]]
    :jsonb #{:data}
    :feature :documents}])
