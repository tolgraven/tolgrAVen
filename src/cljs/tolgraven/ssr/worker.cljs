(ns tolgraven.ssr.worker
  (:require
    ["node:readline" :as readline]
    [reagent.dom.server :as server]
    [reagent.ratom :as ratom]
    [re-frame.core :as rf]
    [re-frame.db :as rfdb]
    [reitit.core :as reitit]
    [tolgraven.db :as db]
    [tolgraven.content.contract :as content]
    [tolgraven.component :as component]
    [tolgraven.component.restore :as restore]
    [tolgraven.render-context :as context]
    [tolgraven.routes :as routes]
    [tolgraven.views.page :as page]
    [tolgraven.views.auto :as auto]
    [tolgraven.blog.module :as blog]
    [tolgraven.cv.module :as cv]
    [tolgraven.docs.module :as docs]
    [tolgraven.ssr.contract :as contract]
    [tolgraven.user.module :as user]
    [tolgraven.link-preview.module :as link-preview]
    [tolgraven.subs]))

(def modules {:cv cv/spec :docs docs/spec :blog blog/spec :user user/spec :link-preview link-preview/spec})

(defn render! [snapshot]
  (let [subscribe rf/subscribe
        *subscriptions (atom {})
        read-sub (fn [args]
                   (or (get @*subscriptions args)
                       (let [*value (ratom/make-reaction
                                     #(deref (apply subscribe args)))]
                         (ratom/run *value)
                         (swap! *subscriptions assoc args *value)
                         *value)))
        snapshot (update snapshot :content content/normalize-content)
        match (assoc (reitit/match-by-path routes/router (:path snapshot)) :query-params (:query-params snapshot))
        view (or (get-in match [:data :view])
                 (get-in modules [(get-in match [:data :module]) :view (get-in match [:data :page])]))]
    (when-not view (throw (js/Error. "No registered page view for SSR")))
    (try
      (reset! context/*snapshot snapshot)
      (reset! context/*interactive? false)
      (restore/begin! {:hydrate? true})
      (reset! rfdb/app-db
        (-> db/data
            (contract/merge-state (contract/snapshot-state snapshot))
            (assoc :content (:content snapshot)
                   :common/route (assoc-in match [:data :view] view))
            (assoc-in [:state :booted :store] true)
            (assoc-in [:state :blog :page] (dec (or (:page snapshot) 1)))
            (assoc-in [:state :blog :current-post-id] (or (:post-id snapshot) (get-in snapshot [:posts 0 :id])))
            (assoc-in [:options :supabase :trusted-author-ids] (:trusted-author-ids snapshot))))
      (binding [*print-fn* #(.write (.-stderr js/process) (str % "\n"))
                context/*server?* true context/*modules* modules
                context/*href* (fn [name params query]
                                (some-> (reitit/match-by-name routes/router name params)
                                        (reitit/match->path query)))]
        ;; Rendering is synchronous and requests are serialized. Browser effects
        ;; cannot enqueue work that would outlive this request's isolated state.
        (with-redefs [rf/dispatch (fn [_] nil)
                      ;; Each query owns a reactive context without sharing
                      ;; component with-let state across the whole React tree.
                      ;; Dispose these request-local readers before clearing db.
                      rf/subscribe
                      (fn
                        ([query] (read-sub [query]))
                        ([query dynamic] (read-sub [query dynamic])))]
          (server/render-to-string [page/<page>])))
      (finally
        (doseq [*subscription (vals @*subscriptions)]
          (ratom/dispose! *subscription))
        (rf/clear-subscription-cache!)
        (reset! rfdb/app-db {})
        (reset! context/*snapshot nil)
        (reset! context/*interactive? true)
        (reset! restore/*context {})))))

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
