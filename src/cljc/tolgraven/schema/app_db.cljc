(ns tolgraven.schema.app-db
  "Partial sections assemble without closing the rest of app-db. Owners can add
   sections; the event adapter checks only changed section references."
  (:require [malli.util :as mu]
            [tolgraven.schema.common :as c]
            [tolgraven.schema.state :as state]
            [tolgraven.page-transition.schema :as page-transition]
            [tolgraven.dev-console.schema :as debug]
            [tolgraven.schema.integrations :as integrations]
            [tolgraven.content.schema :as content]
            [tolgraven.supabase.schema :as store]
            [tolgraven.blog.schema :as blog]
            [tolgraven.validation :as validation]))

(def sections
  (merge blog/sections
    {[:state] state/state
     [:options] state/options
     [:content] (c/optional-map (merge content/sections
                                  {:github integrations/github
                                   :strava (mu/merge (:strava content/sections) integrations/strava)
                                   :instagram (c/optional-map {:posts [:map-of c/id integrations/instagram-post] :error :any})}))
     [:store] store/store
     [:search] integrations/search
     [:docs] [:map-of :string :string]
     [:diagnostics] state/diagnostics
     [:dev-console] debug/state
     [:page/commit] [:maybe page-transition/commit]
     [:common/route] state/route
     [:common/route-last] state/route
     [:component-revisions] [:map-of c/path c/nonnegative]
     ;; Values at these roots are owned by each component/page/module. Their
     ;; :db-schema or :state {:schema ...} adds deeper validation.
     [:component] :map [:page] :map [:module] :map [:global] :map
     [:page-return] (c/optional-map {:status [:enum :ready :unavailable :saving]
                                     :url :string :message :string})
     [:loader] (c/optional-map {:code-ready [:map-of :keyword :boolean] :errors :map})}))

(defonce ^:private *assembled (atom nil))
(defn schema
  "Reuse the current assembled schema without retaining historical section sets."
  [sections]
  (let [[previous assembled] @*assembled]
    (if (= previous sections)
      assembled
      (let [assembled (reduce-kv
                       (fn [schema path section]
                         (mu/merge schema
                           (reduce (fn [child key] [:map [key {:optional true} child]])
                                   section (reverse path))))
                       [:map] sections)]
        (reset! *assembled [sections assembled])
        assembled))))

(defn removed-sections
  "Only explicit removal releases an instance contract; nil remains state.
   Unmounting leaves cached app-db state and its contract available for reuse."
  [paths before after]
  (let [absent #?(:clj (Object.) :cljs (js-obj))]
    (filterv #(and (not (identical? absent (get-in before % absent)))
                  (identical? absent (get-in after % absent))) paths)))

(defn changed-errors [sections before after]
  (if-not (map? after)
    (validation/explain :map after)
    (let [absent #?(:clj (Object.) :cljs (js-obj))]
    (vec
     (mapcat (fn [[path section]]
               (let [old (get-in before path absent) new (get-in after path absent)]
                 (when (and (not (identical? new absent)) (not (identical? old new)))
                   (map #(update % :path (partial into path)) (validation/explain section new)))))
             sections)))))
