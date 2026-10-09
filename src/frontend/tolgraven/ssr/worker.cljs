(ns tolgraven.ssr.worker
  (:require
    [tolgraven.validation.runtime :as validation]
    [tolgraven.ssr.schema :as schema]
    ["node:readline" :as readline]
    [reitit.core :as reitit]
    [tolgraven.db :as db]
    [tolgraven.content.contract :as content]
    [tolgraven.component :as component]
    [tolgraven.navigation.routes :as routes]
    [tolgraven.components.page :as page]
    [tolgraven.modules.home.module :as home]
    [tolgraven.modules.blog.module :as blog]
    [tolgraven.modules.markdown.module :as markdown]
    [tolgraven.modules.highlight.module :as highlight]
    [tolgraven.modules.styled-input.module :as styled-input]
    [tolgraven.modules.data-inspector.module :as inspector]
    [tolgraven.modules.cv.module :as cv]
    [tolgraven.modules.docs.module :as docs]
    [tolgraven.ssr.contract :as contract]
    [tolgraven.components.page-shell :as shell]
    [tolgraven.ssr.render :as render]
    [tolgraven.modules.user.module :as user]
    [tolgraven.modules.link-preview.module :as link-preview]
    [tolgraven.subs]))

(def modules {:home home/spec
              :markdown markdown/spec
              :highlight highlight/spec
              :styled-input styled-input/spec
              :data-inspector inspector/spec
              :cv cv/spec
              :docs docs/spec
              :blog blog/spec
              :user user/spec
              :link-preview link-preview/spec})

(defn render! [snapshot]
  (let [snapshot (update snapshot :content content/normalize-content)
        _ (validation/check! :ssr/snapshot schema/snapshot snapshot)
        match (assoc (reitit/match-by-path routes/router (:path snapshot)) :query-params (:query-params snapshot))
        view (if (:shell? snapshot)
               (fn [] [shell/<page> (get-in match [:data :shell])])
               (or (get-in match [:data :view])
                 (get-in modules [(get-in match [:data :module]) :view (get-in match [:data :page])])))]
    (when-not view (throw (js/Error. "No registered page view for SSR")))
    (binding [*print-fn* #(.write (.-stderr js/process) (str % "\n"))]
      (render/html!
        (-> db/data
            (contract/merge-state (contract/snapshot-state snapshot))
            (assoc :content (:content snapshot)
                   :common/route (assoc-in match [:data :view] view))
            (assoc-in [:state :booted :store] true))
        [page/<page>]
        {:snapshot snapshot :modules modules
         :href (fn [name params query]
                 (some-> (reitit/match-by-name routes/router name params)
                         (reitit/match->path query)))}))))

(defn main []
  (-> (.createInterface readline #js {:input (.-stdin js/process) :crlfDelay js/Infinity})
      (.on "close" (fn [] (.end (.-stdout js/process) #(js/process.exit 0))))
      (.on "line"
           (fn [line]
             (let [response (try
                              {:html (render! (js->clj (js/JSON.parse line) :keywordize-keys true))
                               :module-views (render/module-views modules)}
                              (catch :default error
                                (.write (.-stderr js/process) (str (.-stack error) "\n"))
                                {:error "Page rendering failed"}))]
               (.write (.-stdout js/process) (str (js/JSON.stringify (clj->js response)) "\n")))))))
