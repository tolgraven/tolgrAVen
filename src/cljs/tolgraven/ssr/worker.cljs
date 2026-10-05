(ns tolgraven.ssr.worker
  (:require
    ["node:readline" :as readline]
    [reitit.core :as reitit]
    [tolgraven.db :as db]
    [tolgraven.content.contract :as content]
    [tolgraven.component :as component]
    [tolgraven.routes :as routes]
    [tolgraven.views.page :as page]
    [tolgraven.views.auto :as auto]
    [tolgraven.blog.module :as blog]
    [tolgraven.cv.module :as cv]
    [tolgraven.docs.module :as docs]
    [tolgraven.ssr.contract :as contract]
    [tolgraven.ssr.shell :as shell]
    [tolgraven.ssr.render :as render]
    [tolgraven.user.module :as user]
    [tolgraven.link-preview.module :as link-preview]
    [tolgraven.subs]))

(def modules {:cv cv/spec :docs docs/spec :blog blog/spec :user user/spec :link-preview link-preview/spec})

(defn render! [snapshot]
  (let [snapshot (update snapshot :content content/normalize-content)
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
            (assoc-in [:state :booted :store] true)
            (assoc-in [:state :blog :page] (dec (or (:page snapshot) 1)))
            (assoc-in [:state :blog :current-post-id] (or (:post-id snapshot) (get-in snapshot [:posts 0 :id])))
            (assoc-in [:options :supabase :trusted-author-ids] (:trusted-author-ids snapshot)))
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
                              {:html (render! (js->clj (js/JSON.parse line) :keywordize-keys true))}
                              (catch :default error
                                (.write (.-stderr js/process) (str (.-stack error) "\n"))
                                {:error "Page rendering failed"}))]
               (.write (.-stdout js/process) (str (js/JSON.stringify (clj->js response)) "\n")))))))
