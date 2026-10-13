(ns tolgraven.ssr.local-render
  "Loaded only when the local document cache can save a page."
  (:require [cljs.reader :as reader]
            [tolgraven.validation.runtime :as validation]
            [tolgraven.ssr.schema :as schema]
            [tolgraven.react :as r]
            [reitit.core :as reitit]
            [shadow.lazy :as lazy]
            [tolgraven.loader :as loader]
            [tolgraven.navigation.routes :as routes]
            [tolgraven.loader.styles :as styles]
            [tolgraven.loader.style-catalog :as style-catalog]
            [tolgraven.db :as db]
            [tolgraven.component.storage :as storage]
            [tolgraven.render-context :as context]
            [tolgraven.ssr.contract :as ssr-contract]
            [tolgraven.ssr.return-contract :as contract]
            [tolgraven.ssr.render :as render]
            [tolgraven.components.page :as page]
            [tolgraven.ssr.local :as local]))

(defn- render-pair! [{:keys [state route modules snapshot styles template build owner url title]}]
  (let [encoded (pr-str state)
        _state-valid (when (or (> (contract/byte-count encoded) contract/max-bytes)
                    (not= state (reader/read-string encoded)))
            (throw (js/Error. "Page state cannot be cached")))
        match (merge (reitit/match-by-path routes/router (:path route)) route)
        view (or (get-in match [:data :view])
                 (get-in modules [(get-in match [:data :module]) :view (get-in match [:data :page])]))
        _view-ready (when-not view (throw (js/Error. "Local page view unavailable")))
        *views (atom {})
        saved {:version contract/version
               :url url
               :build build
               :saved-at (.now js/Date)
               :owner owner
               :state-edn encoded
               :modules (vec (keys modules))
               :document-title title}
        render-db (-> (ssr-contract/merge-state db/data state)
                      (assoc :common/route (assoc-in match [:data :view] view)
                             :loader {:code-ready (zipmap (keys modules) (repeat true))}
                             :page/commit nil))
        html (render/html! render-db [page/<page>]
                          {:modules modules
                           :rendered-views *views
                           :restored? true
                           :interactive? true
                           :snapshot snapshot
                           :href (fn [name params query]
                                   (some-> (reitit/match-by-name routes/router name params)
                                           (reitit/match->path query)))})
        saved' (assoc saved :module-views (render/export-inventory @*views))
        links (r/render-to-static-markup
                (into [:<>]
                      (for [href (distinct (mapcat #(style-catalog/paths styles %) (:modules saved)))]
                        [:link {:rel "stylesheet" :href href :data-precedence "modules"}])))
        document (contract/document template html (js/JSON.stringify (clj->js saved')) title links)]
    (when (> (contract/byte-count document) contract/max-bytes)
      (throw (js/Error. "Page document exceeds cache budget")))
    {:op "save" :url url :build build :html document :state state}))

(defn pair! [db config]
  (render-pair!
    (merge config
           {:state (contract/state-for db)
            :route (:common/route db)
            :modules (dissoc (local/modules) :page-capture :page-render :coercion)
            :snapshot @context/*snapshot
            :styles (styles/manifest)
            :owner @storage/*identity
            :url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
            :title (.-title js/document)})))

(defn ^:export worker-pair! [encoded]
  (let [request (reader/read-string encoded)
        _ (validation/check! :page-return/request schema/capture-request request)
        specs (into {} (for [id (distinct (conj (:modules request) :highlight))
                             :let [loadable (get loader/modules id)]
                             :when (and loadable (lazy/ready? loadable))]
                         [id @loadable]))]
    (-> (render-pair! (assoc request :modules specs))
        (dissoc :state)
        clj->js)))
