(ns tolgraven.ssr.return-contract
  "Versioned page/state pair. Transport objects and credentials never belong in
   a render snapshot; persistent component data retains EDN keys and types."
  (:require [clojure.string :as string]))

(def version 1)
(def ttl-ms 1800000)
(def max-bytes (* 2 1024 1024))
(defn byte-count [text]
  #?(:clj (alength (.getBytes ^String text "UTF-8"))
     :cljs (.-length (.encode (js/TextEncoder.) text))))
(def roots [:content :component :page :module :global :docs :search :cookie-notice :hud])
(def transient-state [:debug :global-clicked :is-loading :page-init :navigation :login-field :register-field :form-field
                      :supabase-init :on-booted :supabase-writes])
(defn- without-credentials [value]
  (cond
    (map? value) (into {} (keep (fn [[key child]]
                                (when-not (and (or (keyword? key) (string? key))
                                               (re-matches #"(?i)(password|password[-_]?confirmation|access[-_]?token|refresh[-_]?token|authorization|session|session[-_]?token)" (name key)))
                                  [key (without-credentials child)]))) value)
    (vector? value) (mapv without-credentials value)
    (set? value) (into #{} (map without-credentials) value)
    (seq? value) (doall (map without-credentials value))
    :else value))
(defn source-for [db]
  (-> (select-keys db roots)
      (assoc :state (apply dissoc (:state db) transient-state))
      (update-in [:state :booted] dissoc :store)
      (assoc :options (dissoc (:options db) :supabase))
      (assoc-in [:options :supabase :trusted-author-ids]
                (get-in db [:options :supabase :trusted-author-ids]))
      (assoc :store (select-keys (:store db) [:public :scoped]))))
(defn state-for [db] (without-credentials (source-for db)))

(defn valid? [snapshot {:keys [url build now]}]
  (and (= version (:version snapshot)) (= url (:url snapshot))
       (= build (:build snapshot)) (number? (:saved-at snapshot))
       (<= 0 (- now (:saved-at snapshot)) ttl-ms)
       (string? (:state-edn snapshot)) (<= (byte-count (:state-edn snapshot)) max-bytes)))

(defn script-json [text]
  ;; This is data inside a script element, not interpolated executable code.
  (-> text (string/replace "<" "\\u003c")
      (string/replace "\u2028" "\\u2028") (string/replace "\u2029" "\\u2029")))

(defn document [template html json title]
  ;; Replace only the template slots, in one pass; user content must not become
  ;; another slot even if a post happens to contain one of these strings.
  (string/replace template #"__LOCAL_PAGE_(?:HTML|STATE|TITLE)__"
    {"__LOCAL_PAGE_HTML__" html "__LOCAL_PAGE_STATE__" (script-json json)
     "__LOCAL_PAGE_TITLE__" (-> title (string/replace "&" "&amp;")
                               (string/replace "<" "&lt;") (string/replace "\"" "&quot;"))}))
