(ns website.nested-hydration-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [shadow.lazy :as lazy]
            [website.react :as r]
            [website.loader :as loader]
            [website.loader.styles :as styles]
            [website.component.hydration :as hydration]
            [website.component.restore :as restore]
            [website.render-context :as context]
            [website.validation.runtime :as validation]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(r/defc <inner> [*events]
  (let [[clicked? set-clicked!] (r/use-state false)]
    (swap! *events conj :inner-render)
    (r/use-layout-effect (fn [] (swap! *events conj :inner-commit) nil) [])
    [:button {:data-nested-target true
              :on-click #(set-clicked! true)}
     (if clicked? "Clicked" "Server button")]))

(r/defc <outer> [inner *events mode]
  (swap! *events conj :outer-render)
  (r/use-layout-effect (fn [] (swap! *events conj :outer-commit) nil) [])
  [:article (loader/component-vector {:module inner, :load-on mode} [*events])])

(defn transport! [specs]
  (let [before {:modules loader/modules
                :css styles/acquire!
                :ready? styles/ready?
                :loads @loader/*loads
                :code-loads @loader/*code-loads
                :installed @loader/*installed}
        *started (atom #{})
        *ready (atom #{})
        *releases (atom {})
        gates (into {} (for [id (keys specs)]
                         [id (js/Promise. (fn [resolve _] (swap! *releases assoc id resolve)))]))
        loadables (into {} (for [[id spec] specs]
                             [id (reify lazy/ILoadable
                                   (ready? [_] true)
                                   IDeref (-deref [_] spec))]))]
    (set! loader/modules (merge loader/modules loadables))
    (set! styles/acquire!
      (fn [id]
        (if-let [gate (get gates id)]
          (do (swap! *started conj id) gate)
          ((:css before) id))))
    (set! styles/ready?
      (fn [id]
        (if (contains? specs id)
          (contains? @*ready id)
          ((:ready? before) id))))
    {:started *started
     :release! (fn [id] (swap! *ready conj id) ((get @*releases id) nil))
     :restore! (fn []
                 (set! loader/modules (:modules before))
                 (set! styles/acquire! (:css before))
                 (set! styles/ready? (:ready? before))
                 (reset! loader/*loads (:loads before))
                 (reset! loader/*code-loads (:code-loads before))
                 (reset! loader/*installed (:installed before)))}))

(defn nested-scenario! [mode]
  (let [outer (keyword (str "nested-outer-" (random-uuid)))
        inner (keyword (str "nested-inner-" (random-uuid)))
        specs {outer {:id outer, :view {:view <outer>}}
               inner {:id inner, :view {:view <inner>}}}
        *events (atom [])
        *errors (atom [])
        *root (atom nil)
        before-db @r/*app-db
        before-restore @restore/*context
        before-validation @validation/*enabled?
        element (.createElement js/document "div")
        form (loader/component-vector {:module outer, :load-on :immediate} [inner *events mode])
        transport (transport! specs)]
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
