(ns tolgraven.component-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [tolgraven.test-support :as support]
            [cljs.test :refer-macros [deftest is async]]
            [react :as react]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.ui :as ui]
            [tolgraven.component :as component]
            [tolgraven.component.markup :as markup]
            [tolgraven.component.loading :as loading]
            [tolgraven.component-fixture]
            [tolgraven.util :as util]))
(declare with-root render!)
(defn- flush! [] (support/settle!))
(defc <custom-layout> {:features [:props]} [spec & forms]
  (into [:article.custom-layout [:header "Custom layout"]]
        [[:div.inner (into [:<>] forms)]]))

(defc <contained-content>
  {:container {:tag :section :props {:class "layout" :style {:padding "4px"}}}}
  [spec]
  [:<> [:p "First"] [:p "Second"]])

(defc <custom-contained-content>
  {:container {:view <custom-layout> :props {:class "injected"}}}
  [spec]
  [:p "Custom content"])

(deftest optional-containers-compose-without-manual-body-wrappers
  (async done
    (let [test! (fn [root element]
            (go-promise
              (await! (render! root [<contained-content>
                                    {:props {:class "caller" :style {:color "red"}}}]))
              (let [surface (.querySelector element "section.layout.caller")]
                (is (some? surface))
                (is (= 2 (.-length (.querySelectorAll surface ":scope > p"))))
                (is (= "4px" (.. surface -style -padding)))
                (is (= "red" (.. surface -style -color))))
              (await! (render! root [<custom-contained-content> {:props {:id "custom"}}]))
              (is (some? (.querySelector element "article#custom.custom-layout.injected")))
              (is (= "Custom content" (.-textContent (.querySelector element ".inner"))))
              (await! (render! root [<contained-content>
                                    {:container [:article {:class "template"}
                                                 [:header "Template heading"]
                                                 [:div.slot :container/content]]
                                     :props {:id "caller-layout"}}]))
              (is (some? (.querySelector element "article#caller-layout.layout.template")))
              (is (= 2 (.-length (.querySelectorAll element ".slot > p"))))
              (await! (render! root [<contained-content> {:container [<custom-layout> {:props {:class "supplied"}}]}]))
              (is (= 2 (.-length (.querySelectorAll element "article.supplied .inner > p"))))))]
      (-> (with-root test!)
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest native-attrs-flatten-props-without-leaking-component-options
  (let [child (fn [_] [:p "Child"])
        spec {:props {:class "outer" :style {:color "red"}}
              :class "base" :style {:padding "1rem"}
              :appear "zoom" :depends [{:source :strapi}] :skeleton true}
        form (markup/normalize-form
              [:section spec [:span {:props {:title "A title"} :seen true} "Text"]
               [child spec]])]
    (is (= "base outer" (get-in form [1 :class])))
    (is (= {:padding "1rem" :color "red"} (get-in form [1 :style])))
    (is (= #{:class :style} (set (keys (second form)))))
    (is (= [:span {:title "A title"} "Text"] (nth form 2)))
    (is (= [child spec] (nth form 3)) "Component args retain their specification")))

(deftest rendered-skeleton-retains-the-normal-component-tree
  (async done
    ;; Bind the callback before Promise interop: CLJS async expression lifting
    ;; otherwise awaits the receiver before invoking .catch.
    (let [test! (fn [root element]
                  (go-promise
                    (await! (render! root [loading/<rendered>
                                          {:form [ui/<md->div> "# Sample heading\n\nSample paragraph"]}]))
                    (is (some? (.querySelector element ".component-render-skeleton .md-rendered h1")))
                    (is (= "Sample paragraph" (.-textContent (.querySelector element "p"))))
                    (is (.hasAttribute (.querySelector element ".component-render-skeleton") "inert"))))]
      (-> (with-root test!)
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))
(deftest skeleton-reveal-retains-the-outgoing-layer-and-disposes-it
  (async done
    (let [test! (fn [root element]
                  (go-promise
                    (let [stylesheet (.createElement js/document "link")]
                      (set! (.-rel stylesheet) "stylesheet")
                      (set! (.-href stylesheet) "/css/tolgraven/main.min.css")
                      (try
                        (await! (js/Promise.
                                  (fn [resolve reject]
                                    (set! (.-onload stylesheet) resolve)
                                    (set! (.-onerror stylesheet) reject)
                                    (.appendChild (.-head js/document) stylesheet))))
                        (await! (render! root [component/<loading-reveal>
                                              {:ready? false :skeleton [:p "Sample layout"]}]))
                        (is (= "Sample layout" (.-textContent element)))
                        (await! (render! root [component/<loading-reveal>
                                              {:ready? true :form [:article "Real content"]}]))
                        (is (= "Real content" (.-textContent (.querySelector element "article"))))
                        (when-not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
                          (is (some? (.querySelector element ".component-loading-reveal__exit"))
                              "The actual CSS exit retains the skeleton alongside live content"))
                        ;; Let ordinary commits and the presence lifecycle complete.
                        (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 650))))
                        (is (nil? (.querySelector element ".component-loading-reveal__skeleton")))
                        (is (= "Real content" (.-textContent element)))
                        (finally (.remove stylesheet))))))]
      (-> (with-root test!)
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(deftest cached-skeleton-reveal-starts-with-only-the-live-component
  (async done
    (let [test! (fn [root element]
                  (go-promise
                    (await! (render! root [component/<loading-reveal>
                                          {:ready? true :form [:article "Cached content"]}]))
                    (is (nil? (.querySelector element ".component-loading-reveal__skeleton")))
                    (is (= "Cached content" (.-textContent element)))))]
      (-> (with-root test!)
          (.catch (fn [error] (is false (str error))))
          (.finally done)))))

(defn- render! [root form] (support/render! root form))
(defn- with-root
  [test!]
  (go-promise (let [element (.createElement js/document "div")
                    root (await! (support/create-root! element))]
                (.appendChild (.-body js/document) element)
                (try (await! (test! root element))
                     (finally (support/unmount! root) (.remove element))))))
(defc <counter>
  "Per-instance bindings, metadata and current destructured args."
  {:example true, :features [:props]}
  [spec {:keys [label]} & children]
  :let
  [*count (r/atom 0)]
  (into [:button.counter {:class "base", :style {:color "red"}, :on-click #(swap! *count inc)}
         (str label ":" @*count)]
        children))
(defc <form-two>
  [label]
  (let [*count (r/atom 0)]
    (fn [label] [:button {:on-click #(swap! *count inc)} (str label ":" @*count)])))
(defc <destructured> [{:keys [title]}] [:p title])
(defc <empty-args> [] [:span "No arguments"])
(defc <fragment> [spec] [:<> [:span "Fragment"]])
(defc <component-root> [spec] [<empty-args>])
(defc <empty-element> [spec] [:div])
;; Native React element interop is deliberately the unit under test here.
(defn <native-child> [_] (react/createElement "b" nil "Native child"))
(defc <native-root> [spec] [:> <native-child>])
(defc <raw-root> [spec] [:r> <native-child> #js {}])
(defc <functional-root> [spec] [:f> (fn [] [:b "Functional child"])])
(defc <own-error>
  {:features [:error-boundary :lifecycle]}
  [spec *broken?]
  :let
  [_ (when (:break-init? spec) (throw (js/Error. "Initialization failed")))]
  (when @*broken? (throw (js/Error. "Own render failed")))
  [:p "Recovered own render"])
(defn <throws>
  [*broken?]
  (when @*broken? (throw (js/Error. "Descendant failed")))
  [:p "Recovered descendant"])
(defc <child-error> {:features [:error-boundary]} [spec *broken?] [:div [<throws> *broken?]])
(defc <nested>
  {:features [:error-boundary]}
  [spec *broken?]
  [:article [:p "Healthy parent"] [<own-error> {} *broken?]])
(deftest exported-defc-vars-and-page-navigation-recovery
  (async done
         (-> (go-promise
               (with-redefs [util/log (fn [& _])]
                 (await!
                   (with-root
                     (fn [root element]
                       (go-promise
                         (await! (render! root [ui/<safe> :page [<throws> (r/atom true)] "/blog"]))
                         (is (some? (.querySelector element "[role=alert]")))
                         ;; Changing children on the same route must not retry on every render.
                         (await! (render! root
                                          [ui/<safe> :page [(component/resolve-view #'<empty-args>)]
                                           "/blog"]))
                         (is (some? (.querySelector element "[role=alert]")))
                         ;; A new route owns a new boundary, even if the exported page is a
                         ;; defc Var.
                         (await! (render! root
                                          [ui/<safe> :page [(component/resolve-view #'<empty-args>)]
                                           "/services"]))
                         (is (nil? (.querySelector element "[role=alert]")))
                         (is (= "No arguments" (.-textContent element)))
                         (await! (render! root
                                          [ui/<safe> :page
                                           [(component/resolve-view #'<destructured>)
                                            {:title "Post route"}] "/blog/post/2"]))
                         (is (= "Post route" (.-textContent element)))))))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest local-state-props-fragments-and-argument-forwarding
  (async
    done
    (-> (go-promise
          (await!
            (with-root
              (fn [root element]
                (go-promise
                  (await! (render! root
                                   [<counter>
                                    {:props {:class "caller", :style {:background-color "blue"}},
                                     :classes ["extra"]} {:label "First"} [:i "Child"]]))
                  (let [button (.querySelector element "button")]
                    (doseq [class ["counter" "base" "caller" "extra"]]
                      (is (.contains (.-classList button) class)))
                    (is (= "red" (.. button -style -color)))
                    (is (= "blue" (.. button -style -backgroundColor)))
                    (.click button)
                    (await! (flush!)))
                  (await! (render! root [<counter> {} {:label "Updated"} [:i "New child"]]))
                  (is (= "Updated:1New child" (.-textContent element)))
                  (await! (render! root [<form-two> "First"]))
                  (.click (.querySelector element "button"))
                  (await! (flush!))
                  (await! (render! root [<form-two> "Second"]))
                  (is (= "Second:1" (.-textContent element)))
                  (await! (render! root
                                   [<destructured>
                                    {:title "Domain data", :props {:id "must-not-leak"}}]))
                  (is (= "Domain data" (.-textContent element)))
                  (is (nil? (.querySelector element "#must-not-leak")))
                  (await! (render! root [<fragment> {:props {:id "must-not-leak"}}]))
                  (is (= "SPAN" (.-tagName (.-firstElementChild element))))
                  (await! (render! root [<component-root> {:props {:id "must-not-leak"}}]))
                  (is (= "No arguments" (.-textContent element)))
                  (doseq [[component expected] [[<native-root> "Native child"]
                                                [<raw-root> "Native child"]
                                                [<functional-root> "Functional child"]]]
                    (await! (render! root [component {:props {:id "must-not-leak"}}]))
                    (is (= expected (.-textContent element))))
                  (await! (render! root [<empty-element> {}]))
                  (is (= 0 (.-length (.-childNodes (.-firstElementChild element))))))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest boundaries-isolate-errors-and-retry-with-current-args
  (async
    done
    (->
      (go-promise
        (let [*messages (atom [])]
          (with-redefs [util/log (fn [& message] (swap! *messages conj message))]
            (doseq [component [<own-error> <child-error> <nested>]]
              (await!
                (with-root
                  (fn [root element]
                    (go-promise
                      (let [*broken? (r/atom true)]
                        (await! (render! root
                                         [:div [:aside "Healthy sibling"] [component {} *broken?]]))
                        (is (= 1 (.-length (.querySelectorAll element "[role=alert]"))))
                        (is (.includes (.-textContent element) "Healthy sibling"))
                        (when (= component <nested>)
                          (is (.includes (.-textContent element) "Healthy parent")
                              (.-textContent element)))
                        (reset! *broken? false)
                        (await! (flush!))
                        (is (some? (.querySelector element "[role=alert]"))
                            "Failure stays until explicit retry")
                        (.click (.querySelector element "[role=alert] button"))
                        (await! (flush!))
                        (is (nil? (.querySelector element "[role=alert]")))
                        (is (.includes (.-textContent element) "Recovered"))))))))
            (await! (with-root
                      (fn [root element]
                        (go-promise
                          (let [*broken? (r/atom false)]
                            (await! (render! root [<own-error> {:break-init? true} *broken?]))
                            (is (.includes (.-textContent element) "Initialization failed"))
                            (await! (render! root [<own-error> {} *broken?]))
                            (.click (.querySelector element "button"))
                            (await! (flush!))
                            (is (= "Recovered own render" (.-textContent element))))))))
            (await! (with-root (fn [root element]
                                 (go-promise
                                   (let [*broken? (r/atom true)]
                                     (await! (render! root
                                                      [ui/<safe> :example [<throws> *broken?]]))
                                     (is (some? (.querySelector element "[role=alert]")))
                                     (reset! *broken? false)
                                     (.click (.querySelector element "button"))
                                     (await! (flush!))
                                     (is (= "Recovered descendant" (.-textContent element))))))))
            (is (= 5 (count @*messages)))
            (is (every? #(= :error (first %)) @*messages)))))
      (.catch (fn [error] (is false (str error))))
      (.finally done))))
(deftest lifecycle-uses-current-spec-and-preserves-both-refs
  (async done
         (-> (go-promise
               (let [*events (atom [])
                     *base-ref (atom nil)
                     *caller-ref (atom nil)
                     base-ref! #(reset! *base-ref %)
                     caller-ref! #(reset! *caller-ref %)
                     component (tolgraven.component/create-component
                                 "test"
                                 "refs"
                                 {:features [:props :lifecycle]}
                                 (fn [_] (fn [_] [:div {:ref base-ref!} "Refs"])))]
                 (await! (with-root
                           (fn [root element]
                             (go-promise
                               (await! (render! root
                                                [component
                                                 {:props {:ref caller-ref!},
                                                  :init #(swap! *events conj :init),
                                                  :exit #(swap! *events conj :stale-exit)}]))
                               (is (= (.-firstElementChild element) @*base-ref @*caller-ref))
                               (await! (render! root
                                                [component
                                                 {:props {:ref caller-ref!},
                                                  :exit #(swap! *events conj :latest-exit)}]))))))
                 (is (nil? @*base-ref))
                 (is (nil? @*caller-ref))
                 (is (= [:init :latest-exit] @*events))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(deftest errors-after-mount-and-lifecycle-errors-remain-local
  (async
    done
    (-> (go-promise
          (with-redefs [util/log (fn [& _])]
            (await!
              (with-root (fn [root element]
                           (go-promise (let [*broken? (r/atom false)]
                                         (await! (render! root
                                                          [:div [<counter> {} {:label "Unaffected"}]
                                                           [<own-error> {} *broken?]]))
                                         (.click (.querySelector element "button"))
                                         (await! (flush!))
                                         (reset! *broken? true)
                                         (await! (flush!))
                                         (is (some? (.querySelector element "[role=alert]")))
                                         (is (.includes (.-textContent element) "Unaffected:1"))
                                         (reset! *broken? false)
                                         (.click (.querySelector element "[role=alert] button"))
                                         (await! (flush!))
                                         (is (.includes (.-textContent element) "Unaffected:1"))
                                         (is (nil? (.querySelector element "[role=alert]"))))))))
            (await! (with-root (fn [root element]
                                 (go-promise
                                   (let [*broken? (r/atom false)]
                                     (await! (render! root
                                                      [<own-error>
                                                       {:init (fn [_]
                                                                (throw (js/Error. "Mount failed")))}
                                                       *broken?]))
                                     (is (.includes (.-textContent element) "Mount failed"))
                                     (await! (render! root [<own-error> {} *broken?]))
                                     (.click (.querySelector element "button"))
                                     (await! (flush!))
                                     (is (= "Recovered own render" (.-textContent element))))))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest features-compose-in-order-and-disabled-features-do-not-mount
  (async
    done
    (->
      (go-promise
        (let [*events (atom [])]
          (component/register-feature!
            :test-transform
            {:transform (fn [form _ config] [:section {:data-feature config} form])})
          (component/register-feature! :test-wrapper
                                       {:wrap (fn [form _ config] [:article {:data-feature config}
                                                                   form])})
          (component/register-feature! :test-disabled {:setup (fn [_] (swap! *events conj :setup))})
          (await! (with-root
                    (fn [root element]
                      (go-promise
                        (let [view (component/create-component
                                     "fixture"
                                     "composition"
                                     {:features [[:test-wrapper "outer"] [:test-transform "inner"]
                                                 [:test-disabled false]]}
                                     (fn [] (fn [] [:p "Composed body"])))]
                          (await! (render! root [view]))
                          (is (= "Composed body" (.-textContent element)))
                          (is (some?
                                (.querySelector
                                  element
                                  "article[data-feature=outer] > section[data-feature=inner] > p")))
                          (is (empty? @*events)))))))))
      (.catch (fn [error] (is false (str error))))
      (.finally done))))
(defc <multi-arity> ([label] (<multi-arity> label "!")) ([label suffix] [:p (str label suffix)]))
(defc <unfinished-stub> [])
(deftest lean-components-preserve-render-arities-and-empty-stubs
  (async done
         (-> (go-promise (await! (with-root (fn [root element]
                                              (go-promise
                                                (await! (render! root [<multi-arity> "First"]))
                                                (is (= "First!" (.-textContent element)))
                                                (is (= "P"
                                                       (.-tagName (.-firstElementChild element))))
                                                (await! (render! root [<multi-arity> "Second" "?"]))
                                                (is (= "Second?" (.-textContent element)))
                                                (await! (render! root [<unfinished-stub>]))
                                                (is (nil? (.-firstElementChild element))))))))
             (.catch (fn [error] (is false (str error))))
             (.finally done))))
(defc <prefab-loading> {:loading-prefab :text, :loading-tag :span.loading-name} [] [<loading>])
(defc <inferred-loading> {:depends []} [] [:article.card {:class "card-body"} "Ready"])
(deftest loading-prefabs-and-inferred-root-are-declarative
  (async
    done
    (-> (go-promise
          (await!
            (with-root
              (fn [root element]
                (go-promise
                  (await! (render! root [<prefab-loading>]))
                  (is (some? (.querySelector element "span.loading-name.component-skeleton--text")))
                  (let [options (:options (component/component-spec <inferred-loading>))]
                    (is (= :article.card (:loading-tag options)))
                    (await! (render! root (component/loading-view options)))
                    (is (some? (.querySelector element "article.card.card-body[aria-busy=true]")))
                    (is (= "" (.-textContent element)))))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest hud-updates-retain-message-dom-and-own-the-appearance-feature
  (async done
    (-> (go-promise
          (await!
            (with-root
              (fn [root element]
                (go-promise
                  (let [*messages (r/atom [{:id 1 :level :error :title "Original"}])]
                    (await! (render! root [ui/<hud> *messages]))
                    (let [message (.querySelector element ".hud-message")
                          animation-box (.-parentElement message)]
                      (is (.contains (.-classList animation-box) "zoom-x"))
                      (is (.contains (.-classList animation-box) "slow"))
                      (reset! *messages [{:id 1 :level :error :title "Updated"}
                                         {:id 2 :level :info :title "Another"}])
                      (await! (support/wait-for! #(.includes (.-textContent element) "Updated")))
                      (is (identical? message (.querySelector element ".hud-message"))
                          "Adding or updating notifications must not remount existing messages")
                      (is (= 2 (.-length (.querySelectorAll element ".hud-message")))))))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
