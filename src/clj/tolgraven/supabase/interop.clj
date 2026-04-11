(ns tolgraven.supabase.interop
  (:require
   [tolgraven.platform.supabase :as supabase]
   [tolgraven.store.contract :as contract]
   [tolgraven.supabase.store :as store]))

(defn fetch-contract
  ([] (fetch-contract nil))
  ([_]
   (contract/seed->contract (supabase/fetch-seed))))

(defn write-document!
  ([path data merge-fields]
   (write-document! nil path data merge-fields))
  ([_ path data merge-fields]
   (let [contract (fetch-contract)
         updated (store/set-document contract path data merge-fields)
         seed (contract/contract->seed updated)]
     (doseq [table supabase/delete-order]
       (supabase/reset-table-rest! table))
     (doseq [table supabase/table-order
             :let [rows (get seed (:seed-key (supabase/table-config table)))]]
       (supabase/upsert-rest! table rows))
     (get-in updated path))))
