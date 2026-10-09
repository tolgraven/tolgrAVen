(ns tolgraven.ssr.local-render
  "Loaded only when the local document cache can save a page."
  (:require [cljs.reader :as reader]
            [reagent.dom.server :as server]
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

(defn pair! [db {:keys [template build]}]
  (let [url (str (.-origin js/location) (.-pathname js/location) (.-search js/location))
        state (contract/state-for db)
        encoded (pr-str state)
        ;; Reject non-EDN values rather than hydrating with silently missing data.
        _ (when (or (> (contract/byte-count encoded) contract/max-bytes) (not= state (reader/read-string encoded)))
            (throw (js/Error. "Page state cannot be cached")))
        snapshot {:version contract/version :url url :build build :saved-at (.now js/Date)
                  :owner @storage/*identity
                  :state-edn encoded :modules (vec (keys (local/modules)))
                  :module-views (render/module-views (local/modules))
                  :document-title (.-title js/document)}
        render-db (-> (ssr-contract/merge-state db/data state)
                      (assoc :common/route (:common/route db) :loader (:loader db)
                             :routes (:routes db) :page/commit nil))
        html (render/html! render-db [page/<page>]
                          {:modules (local/modules) :restored? true :interactive? true
                           :snapshot @context/*snapshot})
        stylesheet-links (server/render-to-static-markup
                           (into [:<>]
                                 (for [href (distinct (mapcat #(style-catalog/paths (styles/manifest) %)
                                                             (:modules snapshot)))]
                                   [:link {:rel "stylesheet" :href href :data-precedence "modules"}])))
        document (contract/document template html (js/JSON.stringify (clj->js snapshot))
                                    (.-title js/document) stylesheet-links)]
    (when (> (contract/byte-count document) contract/max-bytes) (throw (js/Error. "Page document exceeds cache budget")))
    {:op "save" :url url :build build :html document :state state}))
