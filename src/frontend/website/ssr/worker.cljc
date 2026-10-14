(ns website.ssr.worker
  #?(:cljs (:require
    [website.validation.runtime :as validation]
    [website.ssr.schema :as schema]
    ["node:readline" :as readline]
    [reitit.core :as reitit]
    [website.db :as db]
    [website.content.contract :as content]
    [website.component :as component]
    [website.navigation.routes :as routes]
    [website.components.page :as page]
    #?(:module/home [website.modules.home.module :as home])
    #?(:module/blog [website.modules.blog.module :as blog])
    #?(:module/markdown [website.modules.markdown.module :as markdown])
    #?(:module/markdown-components [website.modules.markdown-components.module :as authored])
    #?(:module/highlight [website.modules.highlight.module :as highlight])
    #?(:module/styled-input [website.modules.styled-input.module :as styled-input])
    #?(:module/data-inspector [website.modules.data-inspector.module :as inspector])
    #?(:module/cv [website.modules.cv.module :as cv])
    #?(:module/carousel [website.modules.carousel.module :as carousel])
    #?(:module/contact [website.modules.contact.module :as contact])
    #?(:module/settings [website.modules.settings.module :as settings])
    #?(:module/search [website.modules.search.module :as search])
    #?(:module/docs [website.modules.docs.module :as docs])
    [website.ssr.contract :as contract]
    [website.components.page-shell :as shell]
    [website.ssr.render :as render]
    #?(:module/user [website.modules.user.module :as user])
    #?(:module/link-preview [website.modules.link-preview.module :as link-preview])
    [website.subs] )))

#?(:cljs
   (do
(def modules (into {} (remove (comp nil? val)) {:home #?(:module/home home/spec :default nil)
              :markdown #?(:module/markdown markdown/spec :default nil)
              :markdown-components #?(:module/markdown-components authored/spec :default nil)
              :highlight #?(:module/highlight highlight/spec :default nil)
              :styled-input #?(:module/styled-input styled-input/spec :default nil)
              :data-inspector #?(:module/data-inspector inspector/spec :default nil)
              :cv #?(:module/cv cv/spec :default nil)
              :carousel #?(:module/carousel carousel/spec :default nil)
              :contact #?(:module/contact contact/spec :default nil)
              :settings #?(:module/settings settings/spec :default nil)
              :search #?(:module/search search/spec :default nil)
              :docs #?(:module/docs docs/spec :default nil)
              :blog #?(:module/blog blog/spec :default nil)
              :user #?(:module/user user/spec :default nil)
              :link-preview #?(:module/link-preview link-preview/spec :default nil)}))

(defn render!
  ([snapshot] (render! snapshot nil))
  ([snapshot rendered-views]
  (let [snapshot (update snapshot :content content/normalize-content)
        _ (validation/check! :ssr/snapshot schema/snapshot snapshot)
        match (cond-> (assoc (reitit/match-by-path routes/router (:path snapshot))
                             :query-params (:query-params snapshot))
                (:route-parameters snapshot) (assoc :parameters (:route-parameters snapshot)))
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
        {:snapshot snapshot :modules modules :rendered-views rendered-views
         :href (fn [name params query]
                 (some-> (reitit/match-by-name routes/router name params)
                         (reitit/match->path query)))})))))

(defn main []
  (-> (.createInterface readline #js {:input (.-stdin js/process) :crlfDelay js/Infinity})
      (.on "close" (fn [] (.end (.-stdout js/process) #(js/process.exit 0))))
      (.on "line"
           (fn [line]
             (let [response (try
                              (let [*views (atom {})
                                    html (render! (js->clj (js/JSON.parse line) :keywordize-keys true) *views)]
                                {:html html
                                 :module-views (render/export-inventory @*views)})
                              (catch :default error
                                (.write (.-stderr js/process) (str (.-stack error) "\n"))
                                {:error "Page rendering failed"}))]
               (.write (.-stdout js/process) (str (js/JSON.stringify (clj->js response)) "\n")))))))
))
