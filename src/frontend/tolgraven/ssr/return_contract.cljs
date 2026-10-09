(ns tolgraven.ssr.return-contract
  "Versioned page/state pair. Transport objects and credentials never belong in
   a render snapshot; persistent component data retains EDN keys and types."
  (:require [clojure.string :as string]
            [tolgraven.validation :as validation]
            [tolgraven.ssr.schema :as schema]
            [tolgraven.modules.link-preview.schema :as preview]
            [tolgraven.modules.blog.schema :as blog]
            [tolgraven.modules.user.schema :as user]
            [tolgraven.navigation.schema :as navigation]
            [tolgraven.components.shell-schema :as shell]))

(def version 1)
(def ttl-ms 1800000)
(def max-bytes (* 2 1024 1024))
(defn byte-count [text]
  (.-length (.encode (js/TextEncoder.) text)))
(def roots [:content :component :page :module :global :docs :search :cookie-notice :hud])
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
(def return-state-keys
  (into [:motion-seen] (concat shell/return-state-keys user/return-state-keys
                               navigation/return-state-keys)))
(def return-state-sections
  {:blog blog/return-state-keys
   :link-preview preview/return-state-keys})

(defn restored-view-state [state]
  ;; Shared state is opt-in. Runtime measurements, pending work and temporary
  ;; surfaces must not become the first render of a new document.
  (reduce-kv (fn [retained section keys]
               (if-let [value (get state section)]
                 (assoc retained section (select-keys value keys))
                 retained))
             (select-keys state return-state-keys) return-state-sections))

(defn source-for [db]
  (-> (select-keys db roots)
      (assoc :state (restored-view-state (:state db)))
      (assoc :options (dissoc (:options db) :supabase))
      (assoc-in [:options :supabase :trusted-author-ids]
                (get-in db [:options :supabase :trusted-author-ids]))
      (assoc :store (select-keys (:store db) [:public :scoped]))))
(defn state-for [db] (without-credentials (source-for db)))

(defn valid? [snapshot {:keys [url build now]}]
  (and (nil? (validation/explain schema/return-snapshot snapshot))
       (= url (:url snapshot)) (= build (:build snapshot))
       (<= 0 (- now (:saved-at snapshot)) ttl-ms)
       (<= (byte-count (:state-edn snapshot)) max-bytes)))

(defn script-json [text]
  ;; This is data inside a script element, not interpolated executable code.
  (-> text (string/replace "<" "\\u003c")
      (string/replace "\u2028" "\\u2028") (string/replace "\u2029" "\\u2029")))

(defn document [template html json title & [styles]]
  ;; Replace only the template slots, in one pass; user content must not become
  ;; another slot even if a post happens to contain one of these strings.
  (string/replace template #"__LOCAL_PAGE_(?:HTML|STATE|TITLE|STYLES)__"
    {"__LOCAL_PAGE_HTML__" html "__LOCAL_PAGE_STATE__" (script-json json)
     "__LOCAL_PAGE_STYLES__" (or styles "")
     "__LOCAL_PAGE_TITLE__" (-> title (string/replace "&" "&amp;")
                               (string/replace "<" "&lt;") (string/replace "\"" "&quot;"))}))
