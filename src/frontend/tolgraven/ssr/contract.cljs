(ns tolgraven.ssr.contract
  "Snapshot transport shared by the Node renderer and browser hydration."
  (:require [cljs.reader :as edn]
            [tolgraven.modules.blog.ssr :as blog]))

(defn merge-state [db incoming]
  (merge-with (fn [old new] (if (and (map? old) (map? new)) (merge-state old new) new)) db incoming))

(defn snapshot-state [snapshot]
  (let [rows (concat (:posts snapshot) (:comments snapshot))
        state (merge-state
                {:state {:ssr {:hydrating? true
                               :dates (into {} (keep (fn [{:keys [ts date]}] (when date [ts date]))) rows)}}
                 :options {:supabase {:trusted-author-ids (:trusted-author-ids snapshot)}}}
                (blog/public-state snapshot))
        encoded-state (if-let [encoded (:app-db-edn snapshot)]
                        (let [value (edn/read-string encoded)]
                          (when-not (map? value) (throw (ex-info "Invalid public page state" {})))
                          value)
                        {})]
    (merge-state state encoded-state)))
