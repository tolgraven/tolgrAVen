(ns website.nested-hydration-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.loader :as loader]
            [website.module-transport :as transport]
            [website.component.hydration :as hydration]
            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.validation.runtime :as validation]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(r/defc <inner> [*events]
  (let [[clicked? set-clicked!] (r/use-state false)
        inherited? (r/use-context restore/hydrating-context)
        global? (restore/initial-hydration?)]
    (swap! *events conj :inner-render)
    (r/use-layout-effect
      (fn []
        (swap! *events conj :inner-commit [:inner-context global? inherited?])
        nil)
      [])
    [:button {:data-nested-target true
              :on-click #(set-clicked! true)}
     (if clicked? "Clicked" "Server button")]))

(r/defc <context-probe> [*events label]
  (let [inherited? (r/use-context restore/hydrating-context)
        global? (restore/initial-hydration?)]
    (r/use-layout-effect
      (fn [] (swap! *events conj [label global? inherited?]) nil)
      [])
    nil))

(r/defc <outer> [inner *events mode *spa?]
  (swap! *events conj :outer-render)
  (r/use-layout-effect (fn [] (swap! *events conj :outer-commit) nil) [])
  [:article
   [<context-probe> *events :outer-context]
   (loader/component-vector {:module inner, :load-on mode} [*events])
   (when @*spa? [<context-probe> *events :spa-context])])

(defn nested-scenario! [mode]
  (let [outer (keyword (str "nested-outer-" (random-uuid)))
        inner (keyword (str "nested-inner-" (random-uuid)))
        specs {outer {:id outer, :view {:view <outer>}}
               inner {:id inner, :view {:view <inner>}}}
        *events (atom [])
        *spa? (r/atom false)
        *errors (atom [])
        *root (atom nil)
        before-db @r/*app-db
        before-restore @restore/*context
        before-validation @validation/*enabled?
        element (.createElement js/document "div")
        form (loader/component-vector {:module outer, :load-on :immediate} [inner *events mode *spa?])
        transport (transport/install! specs)]
    (.appendChild (.-body js/document) element)
    (reset! validation/*enabled? false)
    (restore/begin! {:hydrate? true})
    (restore/install-module-views! {outer [:view], inner [:view]})
    (set! (.-innerHTML element)
          (binding [context/*server?* true, context/*modules* specs]
            (r/render-to-string form)))
    (reset! *events [])
    (let [article (.querySelector element "article")
          button (.querySelector element "button")]
      (-> (go-promise
            (reset! *root (r/hydrate-root element form
                           {:on-recoverable-error #(swap! *errors conj (.-message %))}))
            (await! (support/wait-for! #(contains? @(:started transport) outer)))
            (is (not (contains? @(:started transport) inner)) "The inner owner cannot precede its parser")
            ;; The real root finishes before a delayed parser's SSR descendants.
            (restore/hydrated!)
            (is (false? (restore/initial-hydration?)))
            ((:release! transport) outer)
            (when (= :hover mode)
              (await! (support/wait-for! #(some #{:outer-commit} @*events)))
              (await! (support/settle!))
              (is (not (contains? @(:started transport) inner)) "Nested SSR retains its declared intent gate")
              (.dispatchEvent button (js/Event. "pointerenter" #js {:bubbles true})))
            (await! (support/wait-for! #(contains? @(:started transport) inner)
                                      "Nested owner awaited its uncommitted ancestor"))
            (is (identical? article (.querySelector element "article")))
            (is (identical? button (.querySelector element "button")))
            ((:release! transport) inner)
            (await! (support/wait-for! #(some #{:inner-commit} @*events)))
            (await! (support/wait-for! #(nil? (.querySelector element "template"))))
            (is (some #{[:outer-context false true]} @*events)
                "The delayed SSR owner inherits hydration after the root completes")
            (is (some #{[:inner-context false true]} @*events)
                "The nested owner retains its own inherited SSR hydration")
            (reset! *spa? true)
            (await! (support/wait-for! #(some #{[:spa-context false false]} @*events)
                                      "A new SPA child inherited completed SSR hydration"))
            (.click button)
            (await! (support/wait-for! #(= "Clicked" (.-textContent button))))
            (is (identical? button (.querySelector element "button")))
            (is (empty? @*errors) (pr-str @*errors)))
        (.catch (fn [error]
                  (is false (str (.-message error) " | renders/commits " (pr-str @*events)
                                 " | acquisitions " (pr-str @(:started transport))
                                 " | recovery " (pr-str @*errors)
                                 " | html " (.-innerHTML element)))))
        (.finally (fn []
                    (when @*root (r/unmount @*root))
                    (.remove element)
                    ((:restore! transport))
                    (hydration/release-all!)
                    (r/clear-subscription-cache!)
                    (reset! r/*app-db before-db)
                    (reset! restore/*context before-restore)
                    (reset! validation/*enabled? before-validation)))))))

(deftest nested-completed-ssr-preserves-nodes-through-delayed-owners
  (async done
    (-> (nested-scenario! :immediate) (.finally done))))

(deftest nested-completed-ssr-retains-intent-activation
  (async done
    (-> (nested-scenario! :hover) (.finally done))))

(deftest hydration-recovery-reaches-the-public-root-callback
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          *errors (atom [])]
      (.appendChild (.-body js/document) element)
      (set! (.-innerHTML element) "<p>Server text</p>")
      (-> (go-promise
            (reset! *root (r/hydrate-root element [:p "Client text"]
                           {:on-recoverable-error #(swap! *errors conj (.-message %))}))
            (await! (support/wait-for! #(seq @*errors) "React recovery callback was dropped"))
            (is (= "Client text" (.-textContent element)))
            (is (some #(re-find #"Hydration|hydration|#418" %) @*errors)))
          (.catch #(is false (.-message %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (.remove element)
                      (done)))))))
