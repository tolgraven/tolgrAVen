(ns tolgraven.schema.declarations
  "Extensible contracts for the existing declaration language, shared by tooling
   and both runtimes. Extension keys stay open; declared keys have real types."
  (:require [malli.util :as mu]
            [tolgraven.schema.common :as c]
            [tolgraven.supabase.schema :as store]))

(def path [:vector {:min 1} :any])
(def event [:cat :keyword [:* :any]])
(def persistence
  (c/optional-map {:scope [:enum :public :user] :version c/positive :ttl-ms c/milliseconds}))
(def state-options
  (c/optional-map {:schema :any :initial :any :key :any :id c/id :scope :keyword
                   :persist [:or :boolean persistence]}))
(def dependency-base
  [:map [:source :keyword]
   [:keys {:optional true} [:vector :keyword]]
   [:path {:optional true} path] [:into {:optional true} path]
   [:query {:optional true} [:or :map [:vector {:min 1} :any]]]
   [:url {:optional true} :string]
   [:ttl-ms {:optional true} [:and number? [:> 0]]]
   [:timeout-ms {:optional true} [:and number? [:> 0]]]
   [:availability {:optional true} [:enum :startup]]
   [:format {:optional true} [:enum :json :text]]
   [:persist {:optional true} [:or :boolean persistence]]])
(def dependency
  [:and dependency-base
   [:multi {:dispatch :source}
    [:strapi [:map [:keys [:vector :keyword]]]]
    [:supabase [:map [:query store/query]]]
    [:subscription [:map [:query c/event]]]
    [:url [:map [:url c/text]]]
    [:app-db [:map [:path c/path]]]
    [:malli.core/default :map]]])
(def dependencies [:sequential dependency])
(def feature [:or :keyword [:tuple :keyword :any]])
(def component
  [:map
   [:schema {:optional true} :any]
   [:args-schema {:optional true} :any]
   [:spec-schema {:optional true} :any]
   [:state {:optional true} state-options]
   [:spec {:optional true} :boolean]
   [:page {:optional true} :boolean]
   [:profile {:optional true} :boolean]
   [:features {:optional true} [:sequential feature]]
   [:depends {:optional true} [:or dependencies fn?]]
   [:loading-prefab {:optional true} :keyword]
   [:loading-tag {:optional true} :keyword]
   [:loading-props {:optional true} :map]
   [:module {:optional true} :keyword]])
(def data-plan
  [:sequential [:map [:id :keyword] [:queries fn?]
                [:depends {:optional true} [:or [:sequential :keyword] fn?]]
                [:transform {:optional true} fn?]]])
(def route-data
  [:map
   [:name {:optional true} :keyword]
   [:module {:optional true} [:maybe :keyword]]
   [:page {:optional true} :keyword]
   [:ssr {:optional true} :boolean]
   [:streaming {:optional true} :boolean]
   [:depends {:optional true} dependencies]
   [:preload-depends {:optional true} [:or dependencies fn?]]
   [:parameters {:optional true} :map]
   [:controllers {:optional true} [:sequential :map]]
   [:data-plan {:optional true} data-plan]
   [:snapshot {:optional true} fn?]
   [:document-title {:optional true} fn?]])

;; Native Reitit trees, including path-only and data-less parent nodes.
(def routes
  [:schema {:registry {::route [:and vector? [:cat :string [:? route-data] [:* [:and [:ref ::route]]]]]}}
   [:sequential [:ref ::route]]])
(def module
  [:map [:id :keyword]
   [:pages {:optional true} routes]
   [:view {:optional true} [:map-of :keyword :any]]
   [:init {:optional true} fn?]
   [:depends {:optional true} dependencies]
   [:preload-modules {:optional true} [:sequential :keyword]]
   [:route-depends {:optional true} fn?]
   [:assets {:optional true} (c/optional-map {:css c/strings :js c/strings})]
   [:db-schema {:optional true} [:map-of path :any]]])


(def component-spec
  "The map supplied to a component instance, distinct from its declaration."
  [:map [:props {:optional true} :map]
        [:depends {:optional true} dependencies]
        [:classes {:optional true} [:or :string [:sequential :string]]]])

(def compose
  "Recursively merge Malli maps. Extension fields refine/replace base fields;
   use [:and base extra] when both constraints must independently hold."
  (memoize (fn [base & extensions]
             (reduce mu/merge base (remove nil? extensions)))))
(defn extend-component [& extensions] (apply compose component extensions))
(defn extend-spec [& extensions] (apply compose component-spec extensions))
(defn extend-module [& extensions] (apply compose module extensions))
(defn extend-page [& extensions] (apply compose route-data extensions))
