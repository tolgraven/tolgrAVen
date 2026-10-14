(ns website.modules.search.storage)

(def tables
  [{:table :website_search_documents
    :sql "supabase/modules/search_queue.sql"
    :key [:id]
    :order [[:id :asc]]}
   {:table :website_search_consumers
    :key [:id]
    :order [[:id :asc]]}
   {:table :website_search_jobs
    :key [:consumer :id]
    :order [[:consumer :asc] [:id :asc]]
    :references #{:website_search_documents :website_search_consumers}}])
