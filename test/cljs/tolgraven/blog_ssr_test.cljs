(ns tolgraven.blog-ssr-test
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [tolgraven.react :as react]
    [re-frame.core :as rf]
    [re-frame.db :as rfdb]
    [reitit.core :as reitit]
    [shadow.lazy :as lazy]
    [tolgraven.db :as db]
    [tolgraven.routes :as routes]
    [tolgraven.loader :as loader]
    [tolgraven.render-context :as context]
    [tolgraven.component.restore :as restore]
    [tolgraven.ssr.client :as client]
    [tolgraven.views.page :as page]
    [tolgraven.blog.module :as blog]
    [tolgraven.cv.module :as cv]
    [tolgraven.docs.module :as docs]
    [tolgraven.user.module :as user]
    [tolgraven.link-preview.module :as link-preview]))

(def modules {:cv cv/spec :docs docs/spec :blog blog/spec :user user/spec :link-preview link-preview/spec})

(r/defc <hydration-check> [on-commit]
  (react/use-effect (fn [] (on-commit) js/undefined) #js [])
  [page/<page>])

(defn check-hydration! [fixture selectors done]
  (let [element (.createElement js/document "div") *root (atom nil)
        *errors (atom []) before @rfdb/app-db before-snapshot @context/*snapshot
        before-restore @restore/*context before-interactive @context/*interactive?
        old-modules loader/modules old-href context/*href* old-dispatch rf/dispatch]
    (.appendChild (.-body js/document) element)
    (set! loader/modules
      (into {} (map (fn [[id spec]]
                      [id (reify lazy/ILoadable (ready? [_] true) IDeref (-deref [_] spec))])) modules))
    (set! context/*href* (fn [name params query]
                          (some-> (reitit/match-by-name routes/router name params) (reitit/match->path query))))
    (set! rf/dispatch (fn [_] nil))
    (-> (js/fetch (str "/js/" fixture "-ssr.json"))
        (.then #(.json %))
        (.then (fn [payload]
                 (let [{:keys [snapshot html]} (js->clj payload :keywordize-keys true)
                       match (reitit/match-by-path routes/router (:path snapshot))
                       view (or (get-in match [:data :view])
                                (get-in modules [(get-in match [:data :module]) :view (get-in match [:data :page])]))
                       script (.createElement js/document "script")]
                   (rf/clear-subscription-cache!)
                   (reset! rfdb/app-db (assoc db/data :content (:content snapshot)
                                              :common/route (assoc-in match [:data :view] view)))
                   (restore/begin! {:hydrate? true})
                   (set! (.-id script) "ssr-bootstrap")
                   (set! (.-type script) "application/json")
                   (set! (.-textContent script) (js/JSON.stringify (clj->js (assoc snapshot :path (.-pathname js/location)))))
                   (.appendChild (.-body js/document) script)
                   (try (client/install!) (finally (.remove script)))
                   (set! (.-innerHTML element) html)
                   (let [nodes (mapv #(.querySelector element %) selectors)]
                     (is (every? some? nodes) "Ordinary page elements exist before hydration")
                     (js/Promise.
                      (fn [resolve reject]
                        (reset! *root
                          (dom/hydrate-root element
                            [<hydration-check>
                             (fn []
                               (try
                                 (doseq [[node selector] (map vector nodes selectors)]
                                   (is (identical? node (.querySelector element selector)) selector))
                                 (is (nil? (.querySelector element ".component-spinner,.loading-spinner")))
                                 (is (empty? @*errors) (pr-str @*errors))
                                 (resolve nil)
                                 (catch :default error (reject error))))]
                            {:on-recoverable-error #(swap! *errors conj (str %))}))))))))
        (.catch #(is false (str %)))
        (.finally (fn []
                    (when @*root (dom/unmount @*root))
                    (.remove element)
                    (rf/clear-subscription-cache!)
                    (set! loader/modules old-modules) (set! context/*href* old-href) (set! rf/dispatch old-dispatch)
                    (reset! rfdb/app-db before) (reset! context/*snapshot before-snapshot)
                    (reset! context/*interactive? before-interactive) (reset! restore/*context before-restore)
                    (done))))))

(deftest ordinary-blog-markup-hydrates-without-replacing-content
  (async done (check-hydration! "blog"
                ["header" "main" ".fading-bg-heading h1" ".blog-post" ".blog-user-avatar"
                 ".blog-post-text p" ".blog-comments" ".blog-comments-inner" ".blog-comment-border" ".blog-powered-by" "footer"] done)))

(deftest ordinary-landing-markup-hydrates-without-replacing-content
  (async done (check-hydration! "landing"
                ["header" "main" "#intro h1" "#top-banner" "#about" "#gallery" "footer"] done)))

(deftest hydration-normalizes-semantic-values-before-enhancing-the-header
  (let [element (.createElement js/document "script") before @client/*snapshot before-db @rfdb/app-db]
    (set! (.-id element) "ssr-bootstrap")
    (set! (.-type element) "application/json")
    (set! (.-textContent element)
          (js/JSON.stringify
           (clj->js {:renderer-version 3 :path (.-pathname js/location) :kind :landing :posts []
                     :content {:header {:menu {:work [["Hire" "/hire" :hire]]}}
                               :blog {:heading {:target :blog}}}})))
    (.appendChild (.-body js/document) element)
    (try
      (let [snapshot (client/install!)]
        (is (= :hire (get-in snapshot [:content :header :menu :work 0 2])))
        (is (= :blog (get-in snapshot [:content :blog :heading :target]))))
      (finally (.remove element) (reset! client/*snapshot before) (reset! rfdb/app-db before-db)
               (reset! context/*interactive? true)))))

(deftest ordinary-cv-markup-hydrates-without-replacing-content
  (async done (check-hydration! "cv" ["main" ".cv-intro" ".cv-skills"] done)))

(deftest ordinary-docs-markup-hydrates-without-replacing-content
  (async done (check-hydration! "docs" ["main" ".codox" ".codox h1"] done)))

(deftest missing-permalink-hydrates-as-a-completed-empty-read
  (async done (check-hydration! "missing" ["header" "main" "footer"] done)))
