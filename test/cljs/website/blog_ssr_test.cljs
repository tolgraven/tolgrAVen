(ns website.blog-ssr-test
  (:require
    [cljs.test :refer-macros [deftest is async]]
    [website.react :as r]




    [reitit.core :as reitit]
    [reitit.frontend :as frontend]
    [shadow.lazy :as lazy]
    [website.db :as db]
    [website.events]
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

(r/defc <hydration-check> [on-commit & [client-lifecycle?]]
  (r/use-effect (fn [] (on-commit) nil) [])
  (restore/document-form
    (if client-lifecycle?
      [client/<hydrate> [page/<page>]]
      [page/<page>])))

(r/defc <formatter-commit> [*commits args]
  (r/use-layout-effect (fn [] (swap! *commits inc) nil) [])
  (into [highlight-view/<formatted-code>] args))

(defn check-hydration! [fixture selectors done & [{:keys [delay-markdown?]}]]
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
        old-lazy-load lazy/load
        markdown-caches (when delay-markdown?
                          [[loader/*loads (find @loader/*loads :markdown)]
                           [loader/*code-loads (find @loader/*code-loads :markdown)]])
        markdown-installed? (contains? @loader/*installed :markdown)
        *markdown-ready? (atom (not delay-markdown?))
        *markdown-started? (atom false)
        *hydration-events (atom [])
        *hydration-state (atom nil)
        *markdown-release (atom nil)
        markdown-gate (when delay-markdown?
                        (js/Promise. (fn [resolve _] (reset! *markdown-release resolve))))
        *markdown-nodes (atom [])
        *markdown-mutations (atom [])
        markdown-selectors [".blog-post-text .md-rendered strong"
                            ".blog-post-text .md-rendered ul li"
                            ".blog-comment-text .md-rendered p"]
        record-markdown! (fn [records]
                           (doseq [record (array-seq records)]
                             (doseq [removed (array-seq (.-removedNodes record))
                                     [selector node] @*markdown-nodes]
                               (when (or (identical? removed node) (.contains removed node))
                                 (swap! *markdown-mutations conj {:type :removed-ssr, :selector selector})))
                             (doseq [added (array-seq (.-addedNodes record))
                                     :when (= 1 (.-nodeType added))
                                     pre (concat (when (= "PRE" (.-tagName added)) [added])
                                                 (array-seq (.querySelectorAll added "pre")))
                                     :when (zero? (.-childElementCount pre))]
                               ;; This ordinary blog fixture has no code blocks.
                               ;; Keep even a detached transient fallback receipt,
                               ;; without relying on its later parent or selector.
                               (swap! *markdown-mutations conj {:type :raw-pre}))))
        observer (when delay-markdown? (js/MutationObserver. (fn [records _] (record-markdown! records))))
        unchanged-markdown! (fn [stage]
                              (record-markdown! (.takeRecords observer))
                              (doseq [[selector node] @*markdown-nodes]
                                (is (and (.contains element node)
                                         (identical? node (.querySelector element selector)))
                                    (str stage " retains " selector)))
                              (is (empty? @*markdown-mutations)
                                  (str stage " never clears SSR or inserts raw fallback: " (pr-str @*markdown-mutations)))
                              (is (nil? (.querySelector element ".md-rendered pre"))
                                  (str stage " keeps ordinary Markdown formatted")))
        original-error (.-error js/console)]
    (.appendChild (.-body js/document) element)
    (set! (.-error js/console)
          (fn [& args]
            (when (some #(and (string? %) (re-find #"hydrated|did not match|didn't match" %)) args)
              (swap! *errors conj (str (first args))))
            (.apply original-error js/console (to-array args))))
    (set! loader/modules
      (into {} (map (fn [[id spec]]
                      [id (reify lazy/ILoadable (ready? [_] (or (not= id :markdown) @*markdown-ready?)) IDeref (-deref [_] spec))])) modules))
    (when delay-markdown?
      (doseq [[cache _] markdown-caches] (swap! cache dissoc :markdown))
      (swap! loader/*installed disj :markdown)
      ;; Match Shadow's real arities and delegate unrelated owners unchanged.
      (let [parser (get loader/modules :markdown)]
        (letfn [(load! ([loadable]
                       (if (identical? parser loadable)
                         (do (reset! *markdown-started? true)
                             (.then markdown-gate (fn [spec] (reset! *markdown-ready? true) spec)))
                         (old-lazy-load loadable)))
                      ([loadable success] (.then (load! loadable) success))
                      ([loadable success failure] (.then (load! loadable) success failure)))]
          (set! lazy/load load!))))
    (set! context/*href* (fn [name params query]
                          (some-> (reitit/match-by-name routes/router name params) (reitit/match->path query))))
    (r/set-dispatch-function!
      (fn [event]
        ;; Forward only this delayed case's actual hydration event. Native
        ;; controller starts dispatch through this same muted boundary, so their
        ;; follow-up state/provider actions remain isolated.
        (when (and delay-markdown? (= :page/hydrated (first event)))
          (swap! *hydration-events conj
                 {:interactive? @context/*interactive?
                  :hydrate? (restore/initial-hydration?)
                  :parser-ready? @*markdown-ready?})
          (old-dispatch event))))
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
                       raw-match (reitit/match-by-path routes/router (:path snapshot))
                       ;; Prefer the same server-typed parameters as native boot;
                       ;; older generated fixtures use native frontend matching.
                       match (if delay-markdown?
                               (if-let [parameters (routes/initial-parameters raw-match snapshot true true)]
                                 (assoc raw-match :parameters parameters)
                                 (frontend/match-by-path routes/router (:path snapshot)))
                               raw-match)
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
                   (when delay-markdown?
                     (is (nil? (:controllers match)) "Initial SSR route has not activated controllers")
                     (is (seq (get-in match [:data :controllers])) "The actual blog route declares controllers")
                     (reset! *markdown-nodes
                             (mapv (fn [selector] [selector (.querySelector element selector)]) markdown-selectors))
                     (is (every? (comp some? second) @*markdown-nodes)
                         "Actual Node blog has formatted post and comment descendants")
                     (is (restore/rendered-view? :markdown :parse)
                         "Actual SSR metadata declares the completed parser export")
                     (.observe observer element #js {:childList true :subtree true}))
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
                                 (catch :default error (reject error))))
                             delay-markdown?]
                            {:on-recoverable-error #(swap! *errors conj (str %))}))))))))
        (.then (fn [_]
                 (when delay-markdown?
                   ;; Observe client/<hydrate>'s actual queued activation; do
                   ;; not replace its reset/flush/dispatch/completion ordering.
                   (-> (support/wait-for!
                         #(and @*markdown-started? @context/*interactive?
                               (not (restore/initial-hydration?))
                               (seq @*hydration-events))
                         "Native hydration lifecycle or parser acquisition did not run")
                       (.then (fn [_]
                                (is (false? @*markdown-ready?) "Parser code is still absent")
                                (is (= [{:interactive? true, :hydrate? true, :parser-ready? false}]
                                       @*hydration-events)
                                    "Native queued activation signals after flush, before completion, with parser code pending")
                                (is (false? (restore/initial-hydration?)))
                                (unchanged-markdown! "Parser pending after root completion")
                                (support/settle!)))
                       (.then (fn [_]
                                (support/mount-subscriptions!
                                  {:route [:common/route]
                                   :hydrating? [:state [:ssr :hydrating?]]})))
                       (.then (fn [state]
                                (reset! *hydration-state state)
                                (support/wait-for!
                                  #(let [{:keys [route hydrating?]} ((:values state))]
                                     (and (false? hydrating?)
                                          (seq (:controllers route))
                                          (= (count (get-in route [:data :controllers]))
                                             (count (:controllers route)))))
                                  "Actual page/hydrated controller field/state did not commit")))
                       (.then (fn [_]
                                (is (false? @*markdown-ready?) "Parser remains held through real controller activation")
                                (is (= "server-rendered-42"
                                       (get-in ((:values @*hydration-state)) [:route :parameters :path :permalink]))
                                    "Native controller receives the actual matched permalink")
                                (unchanged-markdown! "Parser remains pending across committed work")
                                (@*markdown-release markdown/spec)
                                (support/wait-for!
                                  #(and @*markdown-ready? (loader/code-spec :markdown)
                                        (nil? (.querySelector element "template[data-module-boundary='markdown']")))
                                  "Native parser owners did not commit after code release")))
                       (.then (fn [_]
                                (unchanged-markdown! "Parser hydrated")
                                (is (empty? @*errors) (pr-str @*errors))))))))
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
                    ;; Drain before cleanup so unmount is not a hydration receipt.
                    (when observer
                      (record-markdown! (.takeRecords observer))
                      (is (empty? @*markdown-mutations)
                          "No transient fallback or SSR removal occurred before unmount")
                      (.disconnect observer))
                    (when @*hydration-state ((:unmount! @*hydration-state)))
                    (when @*root (r/unmount @*root))
                    (let [finish! (fn []
                                  (when delay-markdown?
                                    (set! lazy/load old-lazy-load)
                                    (doseq [[cache entry] markdown-caches]
                                      (swap! cache #(if entry (assoc % :markdown (val entry)) (dissoc % :markdown))))
                                    (swap! loader/*installed (if markdown-installed? conj disj) :markdown))
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
                                  (-> (support/settle!) (.then (fn [_] (done)))))]
                      (if delay-markdown?
                        (let [pending #js [(get @loader/*code-loads :markdown)
                                           (get @loader/*loads :markdown)]]
                          ;; Retire the owned transport while dispatch is still
                          ;; muted, before restoring caches/handlers for the next
                          ;; fixture. This also drains a failed assertion path.
                          (@*markdown-release markdown/spec)
                          (-> (js/Promise.all pending)
                              (.catch (fn [_] nil))
                              (.then (fn [_] (support/settle!)))
                              (.then (fn [_] (finish!)))))
                        (finish!))))))))

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

(deftest completed-node-markdown-survives-delayed-parser-hydration
  (async done
    (check-hydration! "blog"
      ["header" "main" ".blog-post-text .md-rendered strong"
       ".blog-post-text .md-rendered ul li" ".blog-comment-text .md-rendered p"]
      done {:delay-markdown? true})))
