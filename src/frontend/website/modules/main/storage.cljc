(ns website.modules.main.storage
  "Seed persistence owned by the main module; portable declaration data.")

(def tables
  [{:table :site_media
    :sql "supabase/modules/site_media.sql"
    :seed-key :site_media
    :collections #{"media-assets"}
    :queries {"media-assets" {:document-key :id
                              :fields {:id :id
                                       :src :source_url
                                       :canonical-src :canonical_src
                                       :status :status
                                       :updated-at :updated_at}
                              :batch-field :src}}
    :key [:id]
    :order [[:updated_at :asc] [:id :asc]]
    :select [:id :source_url :canonical_src :status :asset :updated_at]
    :jsonb #{:asset :metadata}
    :public-columns "id,source_url,canonical_src,status,asset,updated_at"
    :feature :media}
   {:table :service_configs
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
