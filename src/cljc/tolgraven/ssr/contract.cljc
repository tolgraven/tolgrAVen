(ns tolgraven.ssr.contract
  (:require #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [tolgraven.content.contract :as content]
            [tolgraven.supabase.query :as query]
            [tolgraven.main.pages :as main-pages]
            [reitit.core :as reitit]))

(def landing-paths
  (into #{} (comp (filter #(= :landing (:kind (second %)))) (map first))
        (reitit/routes (reitit/router main-pages/spec))))

;; The ordinary client layout and SSR use the same ordering. :init entries are
;; browser lifecycle instructions, not extra server-rendered DOM.
(def landing-layout
  [:intro [:interlude 0] :services [:init :services] [:interlude 1]
   :moneyshot [:init :instagram] [:init :strava] :story [:interlude 2]
   [:init :soundcloud] :strava [:init :gallery] :soundcloud :instagram
   :gallery :github :gpt :chat])

(def landing-spec
  {:id :landing :layout landing-layout
   :depends [{:source :strapi :keys (content/keys-for-route :home)}]
   :deferred-modules #{:strava :soundcloud :instagram :github :gpt :chat}})

(defn page-spec [uri]
  (when (landing-paths uri) landing-spec))

(declare merge-state)

(defn- records [rows]
  (into {} (keep (fn [row] (when (:id row) [(str (:id row)) (dissoc row :author :date)]))) rows))

(defn snapshot-state [snapshot]
  (let [rows (concat (:posts snapshot) (:comments snapshot))
        public {"users" (records (keep :author rows))
                "blog-posts" (merge (records (:summaries snapshot)) (records (:posts snapshot)))
                "blog-comments" (records (:comments snapshot))}
        state (cond-> {:store {:public public
                                      :scoped (into {} (map (fn [[id profile]]
                                                            [(pr-str (query/normalize-query (query/profile-query id)))
                                                             {:docs [{:id id :data profile}]}]))
                                                    (get public "users"))}
                       :state {:ssr {:hydrating? true
                                     :dates (into {} (keep (fn [{:keys [ts date]}] (when date [ts date]))) rows)}}}
                (#{:blog "blog"} (:kind snapshot))
                ;; A fresh SSR page owns its initial display state. If the saved
                ;; path hint did not match, older folds/window sizes must not
                ;; change the markup while hydrating that public snapshot.
                (assoc-in [:state :blog] {:comment-limit {} :comment-thread-expanded {}}))
        encoded-state (if-let [encoded (:app-db-edn snapshot)]
                        (let [value (edn/read-string encoded)]
                          (when-not (map? value) (throw (ex-info "Invalid public page state" {})))
                          value)
                        {})]
    (merge-state state encoded-state)))

(defn merge-state [db incoming]
  (merge-with (fn [old new] (if (and (map? old) (map? new)) (merge-state old new) new)) db incoming))
