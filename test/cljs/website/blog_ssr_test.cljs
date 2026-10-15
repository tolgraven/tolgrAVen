(ns website.blog-ssr-test
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [website.react :as r]




    [reitit.core :as reitit]
    [shadow.lazy :as lazy]
    [website.db :as db]
    [website.navigation.routes :as routes]
    [website.loader :as loader]
    [website.loader.styles :as styles]
    [website.browser-resources :as resources]
    [website.test-support :as support]
    [website.render-context :as context]
    [website.component.restore :as restore]
    [website.ssr.client :as client]
    [website.components.page :as page]
    [website.modules.blog.module :as blog]
    [website.modules.markdown.module :as markdown]
    [website.modules.markdown-components.module :as authored]
    [website.modules.settings.module :as settings]
    [website.modules.search.module :as search]
    [website.modules.styled-input.module :as styled-input]
    [website.modules.home.module :as home]
    [website.modules.cv.module :as cv]
    [website.modules.carousel.module :as carousel]
    [website.modules.docs.module :as docs]
    [website.modules.user.module :as user]
    [website.modules.highlight.module :as highlight]
    [website.modules.highlight.views :as highlight-view]
    [website.modules.link-preview.module :as link-preview]))

(def modules
  {:home home/spec
   :cv cv/spec
   :carousel carousel/spec
   :docs docs/spec
   :blog blog/spec
   :markdown markdown/spec
   :markdown-components authored/spec
   :settings settings/spec
   :search search/spec
   :styled-input styled-input/spec
   :user user/spec
   :highlight highlight/spec
   :link-preview link-preview/spec})

(r/defc <hydration-check> [on-commit]
  (r/use-effect (fn [] (on-commit) nil) [])
  (restore/document-form [page/<page>]))

(r/defc <formatter-commit> [*commits args]
  (r/use-layout-effect (fn [] (swap! *commits inc) nil) [])
  (into [highlight-view/<formatted-code>] args))

(defn check-hydration! [fixture selectors done]
  (let [element (.createElement js/document "div") *root (atom nil)
        *errors (atom []) before @r/*app-db before-snapshot @context/*snapshot
        before-restore @restore/*context before-interactive @context/*interactive?
        old-modules loader/modules old-href context/*href* old-dispatch (r/dispatch-function)
        old-acquire loader/acquire-code! old-after-page resources/after-page!
        *highlight-requests (atom []) *release (atom []) *code-nodes (atom nil)
        *formatter-commits (atom 0)
        *code-stages (atom [])
        code-selectors [".code-block pre" ".code-block span" ".code-block code.language-cpp"
                        "p > .code-snippet > code.code-highlight" "p > .code-snippet > code.code-highlight span[style]"]
        record-stage! (fn [stage]
                        (let [pre (.querySelector element ".code-block pre")
                              text (or (some-> pre .-textContent) "")
                              markup (or (some-> pre .-outerHTML) "")
                              body (or (some-> (.querySelector element ".blog-post-text") .-textContent) "")]
                          (swap! *code-stages conj
                            {:stage stage
                             :same-nodes (mapv (fn [node selector]
                                                 (identical? node (.querySelector element selector)))
                                               @*code-nodes code-selectors)
                             :pending-text (subs text 0 (min 120 (count text)))
                             :pre-html (subs markup 0 (min 1200 (count markup)))
                             :body-text (subs body 0 (min 160 (count body)))
                             :markdown-boundaries (.-length (.querySelectorAll element "template[data-module-boundary='markdown']"))
                             :gates (count @*release)
                             :formatter-commits @*formatter-commits
                             :recovery-count (count @*errors)
                             :recoveries (mapv #(subs % 0 (min 240 (count %))) (take-last 2 @*errors))})))
        original-error (.-error js/console)]
    (.appendChild (.-body js/document) element)
    (set! (.-error js/console)
          (fn [& args]
            (when (some #(and (string? %) (re-find #"hydrated|did not match|didn't match" %)) args)
              (swap! *errors conj (str (first args))))
            (.apply original-error js/console (to-array args))))
    (set! loader/modules
      (into {} (map (fn [[id spec]]
                      [id (reify lazy/ILoadable (ready? [_] true) IDeref (-deref [_] spec))])) modules))
    (set! context/*href* (fn [name params query]
                          (some-> (reitit/match-by-name routes/router name params) (reitit/match->path query))))
    (r/set-dispatch-function! (fn [_] nil))
    (set! resources/after-page! (fn [release!] (swap! *release conj release!) (fn [])))
    (set! loader/acquire-code!
      (fn [id]
        (if (= id :highlight)
          (do
            (swap! *highlight-requests conj id)
            (js/Promise.resolve
              (assoc-in highlight/spec [:view :formatted-code]
                        (fn [& args] [<formatter-commit> *formatter-commits args]))))
          (old-acquire id))))
    ;; Fixtures are regenerated from the current Node bundle; never hydrate
    ;; cached HTML from a previous component graph.
    (-> (js/fetch (str "/js/" fixture "-ssr.json") #js {:cache "no-store"})
        (.then #(.json %))
        (.then (fn [payload]
                 (let [snapshot (:snapshot (js->clj payload :keywordize-keys true))
                       match (reitit/match-by-path routes/router (:path snapshot))]
                   ;; Production installs route dependency styles in the head.
                   ;; Await the same catalog here before hydrating its HTML.
                   (-> (styles/acquire! (get-in match [:data :module]))
                       (.then (fn [_] payload))))))
        (.then (fn [payload]
                 (let [{:keys [snapshot html]} (js->clj payload :keywordize-keys true)
                       match (reitit/match-by-path routes/router (:path snapshot))
                       view (or (get-in match [:data :view])
                                (get-in modules [(get-in match [:data :module]) :view (get-in match [:data :page])]))
                       script (.createElement js/document "script")]
                   (r/clear-subscription-cache!)
                   (reset! r/*app-db (assoc db/data :content (:content snapshot)
                                              :common/route (assoc-in match [:data :view] view)))
                   (restore/begin! {:hydrate? true})
                   (set! (.-id script) "ssr-bootstrap")
                   (set! (.-type script) "application/json")
                   (set! (.-textContent script) (js/JSON.stringify (clj->js (assoc snapshot :path (.-pathname js/location)))))
                   (.appendChild (.-body js/document) script)
                   (try (client/install!) (finally (.remove script)))
                   (set! (.-innerHTML element) html)
                   (when (= fixture "code-blog")
                     (reset! *code-nodes (mapv #(.querySelector element %) code-selectors))
                     (record-stage! :server-markup))
                   (let [nodes (mapv #(.querySelector element %) selectors)
                         images (mapv (fn [wrapper]
                                        [wrapper (.getAttribute wrapper "data-image-key")
                                         (.getPropertyValue (.-style wrapper) "--image-final-opacity")])
                                      (array-seq (.querySelectorAll element ".progressive-image")))
                         letter (.querySelector element ".intro-title-letter")
                         animations (when letter (vec (array-seq (.getAnimations letter))))]
                     (is (every? some? nodes) "Ordinary page elements exist before hydration")
                     (js/Promise.
                      (fn [resolve reject]
                        (reset! *root
                          (r/hydrate-root element
                            [<hydration-check>
                             (fn []
                               (try
                                 (doseq [[node selector] (map vector nodes selectors)]
                                   (is (identical? node (.querySelector element selector)) selector))
                                 (doseq [[wrapper token opacity] images]
                                   (is (.contains element wrapper) "Selective hydration retains each native image wrapper")
                                   (is (= token (.getAttribute wrapper "data-image-key")))
                                   (is (= opacity (.getPropertyValue (.-style wrapper) "--image-final-opacity"))
                                       "Each native image retains its Node-rendered identifier through module hydration"))
                                 (when letter
                                   (is (seq animations) "SSR starts the letter clock before hydration")
                                   (is (= animations (vec (array-seq (.getAnimations letter))))
                                       "Hydration retains the same native letter animations"))
                                 (is (nil? (.querySelector element ".component-spinner,.loading-spinner")))
                                 (is (empty? @*errors) (pr-str @*errors))
                                 (is (empty? @*highlight-requests) "The page hydrates before formatter acquisition")
                                 (resolve nil)
                                 (catch :default error (reject error))))]
                            {:on-recoverable-error #(swap! *errors conj (str %))}))))))))
        (.then (fn [_]
                 (when (= fixture "code-blog")
                   (restore/hydrated!)
                   ;; Root completion does not mean the lazy parser's controls
                   ;; have committed. Its inherited hydration must keep every
                   ;; formatter gated while those controls become interactive.
                   (-> (support/wait-for!
                         #(let [blocks (.-length (.querySelectorAll element ".code-block, .code-highlight"))]
                            (and (pos? blocks)
                                 (.querySelector element ".code-block button[aria-pressed]")
                                 (= blocks (count @*release))))
                         "Delayed Markdown did not retain its formatter gates")
                       (.then (fn [_]
                                (record-stage! :controls-ready-before-wrap)
                                (doseq [[node selector] (map vector @*code-nodes code-selectors)]
                                  (is (identical? node (.querySelector element selector))
                                      (str "Before wrap " selector " | " (pr-str @*code-stages))))
                                (is (false? (restore/initial-hydration?)))
                                (is (empty? @*highlight-requests)
                                    "Late parser hydration still defers formatter acquisition")
                                (.click (.querySelector element ".code-block button[aria-pressed]"))
                                (support/wait-for! #(.querySelector element ".code-block-wrapped")
                                                   "Hydrated code controls did not respond before formatting")))
                       (.then (fn [_]
                                (record-stage! :after-wrap-before-formatter)
                                (doseq [[node selector] (map vector @*code-nodes code-selectors)]
                                  (is (identical? node (.querySelector element selector))
                                      (str selector " | " (pr-str @*code-stages))))
                                (is (empty? @*highlight-requests) "Wrapping does not advance the gate")
                                (doseq [release! @*release] (release!))
                                (support/wait-for!
                                  #(= (.-length (.querySelectorAll element ".code-block, .code-highlight"))
                                      @*formatter-commits))))
                       (.then (fn [_]
                                (record-stage! :after-formatter)
                                (doseq [[node selector] (map vector @*code-nodes code-selectors)]
                                  (is (identical? node (.querySelector element selector))
                                      (str selector " | " (pr-str @*code-stages))))
                                (is (empty? @*errors) (pr-str @*errors))))))))
        (.catch #(is false (str %)))
        (.finally (fn []
                    (when @*root (r/unmount @*root))
                    (.remove element)
                    (set! (.-error js/console) original-error)
                    (r/clear-subscription-cache!)
                    (set! loader/modules old-modules) (set! context/*href* old-href) (r/set-dispatch-function! old-dispatch)
                    (set! loader/acquire-code! old-acquire) (set! resources/after-page! old-after-page)
                    (reset! r/*app-db before) (reset! context/*snapshot before-snapshot)
                    (reset! context/*interactive? before-interactive) (reset! restore/*context before-restore)
                    ;; Reader disposal can queue a drain while this fixture's
                    ;; dispatch is muted. Release that queue through its owner
                    ;; after restoring dispatch, before the next test acquires it.
                    (r/dispatch [:store/drain-readers])
                    (-> (support/settle!) (.then (fn [_] (done)))))))))

(deftest ordinary-blog-markup-hydrates-without-replacing-content
  (async done (check-hydration! "blog"
                ["header" "main" ".fading-bg-heading h1" ".blog-post" ".blog-user-avatar"
                 ".blog-post-text p" ".blog-comments" ".blog-comments-inner" ".blog-comment-border" ".blog-powered-by" "footer"] done)))

(deftest ordinary-landing-markup-hydrates-without-replacing-content
  (async done (check-hydration! "landing"
                ["header" "main" "#intro h1" ".intro-title-letter" "#logo-top" "#top-banner" "#about" "#gallery" "footer"] done)))

(deftest focused-old-comment-hydrates-with-its-visible-ancestors
  (async done (check-hydration! "focused-comment"
                ["main" ".blog-post" "#blog-comment-old-root" "#blog-comment-old-child" "#blog-comment-113"] done)))

(deftest standalone-comment-subtree-hydrates-without-a-post-or-neighbors
  (async done (check-hydration! "standalone-comment"
                ["main" "#standalone-comment" "#blog-comment-113" "#blog-comment-114"] done)))

(deftest highlighted-code-hydrates-without-replacing-its-native-roots
  (async done (check-hydration! "code-blog"
                ["header" "main" ".blog-post-text .code-block pre"
                 ".blog-post-text pre code" ".blog-post-text .code-block span"] done)))

(deftest hydration-normalizes-semantic-values-before-enhancing-the-header
  (let [element (.createElement js/document "script") before @client/*snapshot before-db @r/*app-db]
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
      (finally (.remove element) (reset! client/*snapshot before) (reset! r/*app-db before-db)
               (reset! context/*interactive? true)))))

(deftest ordinary-cv-markup-hydrates-without-replacing-content
  (async done (check-hydration! "cv" ["main" ".cv-intro" ".cv-skills"] done)))

(deftest ordinary-docs-markup-hydrates-without-replacing-content
  (async done (check-hydration! "docs" ["main" ".codox" ".codox h1"] done)))

(deftest missing-permalink-hydrates-as-a-completed-empty-read
  (async done (check-hydration! "missing" ["header" "main" "footer"] done)))

(deftest authored-components-hydrate-with-their-real-native-roots
  (async done (check-hydration! "authored-blog"
                [".content-box" ".content-box p" "picture img" ".content-config" "[role=switch]" ".content-search input"] done)))
