(ns tolgraven.schema.declarations
  "Extensible contracts for the existing declaration language, shared by tooling
   and both runtimes. Extension keys stay open; declared keys have real types."
  (:require [tolgraven.schema.common :as c]
            [tolgraven.supabase.schema :as store]
            [tolgraven.supabase.catalog :as catalog]))

(def path [:vector {:min 1} :any])
(def event [:cat :keyword [:* :any]])
(def persistence
  (c/optional-map {:scope [:enum :public :user] :version c/positive :ttl-ms c/milliseconds :consume? :boolean}))
(def state-options
  (c/optional-map {:schema :any :initial :any :key :any :id c/id :scope :keyword
                   :persist [:or :boolean persistence]}))
(def dependency-base
  [:map [:source :keyword]
   [:?keys [:vector :keyword]]
   [:?path path] [:?into path]
   [:?query [:or :map [:vector {:min 1} :any]]]
   [:?url :string]
   [:?ttl-ms [:and number? [:> 0]]]
   [:?timeout-ms [:and number? [:> 0]]]
   [:?availability [:enum :startup]]
   [:?format [:enum :json :text]]
   [:?persist [:or :boolean persistence]]])
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
(def container
  [:or [:= false] [:vector {:min 1} :any]
   [:map
    [:?tag [:or :keyword :string]]
    [:?props :map]
    [:?view :any]
    [:?form [:vector {:min 1} :any]]]])
(def icons [:vector [:re "^(?:brands|solid)/[a-z0-9-]+$"]])

(def component
  [:map
   [:?schema :any]
   [:?args-schema :any]
   [:?spec-schema :any]
   [:?state [:or state-options fn?]]
   [:?spec :boolean]
   [:?page :boolean]
   [:?container container]
   [:?profile :boolean]
   [:?icons icons]
   [:?features [:sequential feature]]
   [:?depends [:or dependencies fn?]]
   [:?loading-prefab :keyword]
   [:?loading-tag :keyword]
   [:?loading-props :map]
   [:?module :keyword]])
(def data-plan
  [:sequential [:map [:id :keyword] [:queries fn?]
                [:?depends [:or [:sequential :keyword] fn?]]
                [:?transform fn?]]])
(def route-data
  [:map
   [:?name :keyword]
   [:?module :?keyword]
   [:?page :keyword]
   [:?icons icons]
   [:?ssr :boolean]
   [:?streaming :boolean]
   [:?depends dependencies]
   [:?preload-depends [:or dependencies fn?]]
   [:?parameters :map]
   [:?controllers [:sequential :map]]
   [:?data-plan data-plan]
   [:?snapshot fn?]
   [:?document-title fn?]])

;; Native Reitit trees, including path-only and data-less parent nodes.
(def routes
  [:schema {:registry {::route [:and vector? [:cat :string [:? route-data] [:* [:and [:ref ::route]]]]]}}
   [:sequential [:ref ::route]]])
(def module
  [:map [:id :keyword]
   [:?client [:map-of :keyword fn?]]
   [:?content [:vector :keyword]]
   [:?server [:map
                             [:?routes :symbol]
                             [:?requires [:set :keyword]]
                             [:?requires-features [:map-of :keyword [:set :keyword]]]
                             [:?sql c/strings]
                             [:?sql-without-comments c/strings]]]
   [:?storage [:vector catalog/table]]
   [:?pages routes]
   [:?view [:map-of :keyword :any]]
   [:?init fn?]
   [:?install fn?]
   [:?styles c/strings]
   [:?icons icons]
   [:?ssr-styles [:enum :initial :deferred]]
   [:?depends dependencies]
   [:?preload-modules [:sequential :keyword]]
   [:?route-depends fn?]
   [:?assets (c/optional-map {:css c/strings :js c/strings})]
   [:?db-schema [:map-of path :any]]])


(def component-spec
  "The map supplied to a component instance, distinct from its declaration."
  [:map [:?props :map]
        [:?features [:sequential feature]]
        [:?container container]
        [:?depends dependencies]
        [:?classes [:or :string [:sequential :string]]]])

(def compose
  "Recursively merge Malli maps. Extension fields refine/replace base fields;
   use [:and base extra] when both constraints must independently hold."
  (memoize (fn [base & extensions]
             (into [:merge base] (remove nil? extensions)))))
(defn extend-component [& extensions] (apply compose component extensions))
(defn extend-spec [& extensions] (apply compose component-spec extensions))
(defn extend-module [& extensions] (apply compose module extensions))
(defn extend-page [& extensions] (apply compose route-data extensions))
