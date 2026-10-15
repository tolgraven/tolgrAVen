(ns website.shell-hydration-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [async deftest is]]
            [website.component.restore :as restore]
            [website.components.shell :as shell]
            [website.events]
            [website.loader :as loader]
            [website.module-transport :as transport]
            [website.modules.user.subs]
            [website.react :as r]
            [website.render-context :as context]
            [website.subs]
            [website.test-support :as support]
            [website.validation.runtime :as validation]))

(r/defc <deferred-content> [*committed?]
  (r/use-layout-effect (fn [] (reset! *committed? true) nil) [])
  [:p {:data-deferred-shell-sibling true} "Completed server content"])

(r/defc <native-shell> [module *shell-committed? *sibling-committed?]
  (r/use-layout-effect (fn [] (reset! *shell-committed? true) nil) [])
  [:<>
   [shell/<header> {:menu {:work [], :personal []}}]
   (loader/component-vector {:module module, :load-on :immediate} [*sibling-committed?])
   [shell/<footer> [{:id "hydration", :title "Native sticky footer"}]]])

(deftest pending-ssr-sibling-does-not-delay-native-shell-visibility
  (async done
    (let [restore-db! (r/make-restore-fn)
          before-context @restore/*context
          before-interactive? @context/*interactive?
          before-validation? @validation/*enabled?
          before-flush (r/batching-flush)
          style (.-style (.-documentElement js/document))
          before-css (into {} (for [property ["--header-height-current" "--footer-height-current"]]
                                [property [(.getPropertyValue style property)
                                           (.getPropertyPriority style property)]]))
          module (keyword (str "shell-hydration-sibling-" (random-uuid)))
          specs {module {:id module, :view {:view <deferred-content>}}}
          acquisition (transport/install! specs)
          element (.createElement js/document "div")
          *root (atom nil)
          *errors (atom [])
          *shell-committed? (atom false)
          *sibling-committed? (atom false)
          form [<native-shell> module *shell-committed? *sibling-committed?]]
      (.appendChild (.-body js/document) element)
      ;; Keep unrelated Search/User fallback owners closed. Only this test's real
      ;; completed SSR module is pending; its native loader retains the gate.
      (reset! context/*interactive? false)
      (reset! validation/*enabled? false)
      (r/dispatch-sync [:init/app-db])
      (r/dispatch-sync [:state [:hidden] {:header false, :footer false}])
      (r/dispatch-sync [:state [:scroll] {:past-top true, :at-bottom false}])
      (restore/begin! {:hydrate? true})
      (restore/install-module-views! {module [:view]})
      (set! (.-innerHTML element)
            (binding [context/*server?* true, context/*modules* specs]
              (r/render-to-string form)))
      (let [header (.querySelector element "header")
            footer (.querySelector element "#footer-sticky")
            sibling (.querySelector element "[data-deferred-shell-sibling]")
            hidden? #(and (.contains (.-classList header) "hide")
                          (.contains (.-classList footer) "hide"))]
        (-> (go-promise
              (is (every? some? [header footer sibling]) "Native completed server markup is present")
              (is (not (hidden?)))
              (reset! *root (r/hydrate-root element form
                             {:on-recoverable-error #(swap! *errors conj (.-message %))}))
              (await! (support/wait-for! #(and @*shell-committed?
                                              (contains? @(:started acquisition) module))
                                        "Shell did not commit beside its pending SSR sibling"))
              (restore/hydrated!)
              (is (false? @*sibling-committed?))
              (is (identical? sibling (.querySelector element "[data-deferred-shell-sibling]"))
                  "Pending hydration retains completed SSR content")
              (r/dispatch-sync [:hide-header-footer true true])
              (is (true? @(r/subscribe [:state [:hidden :header]]))
                  "The real event's header state was accepted")
              (is (true? @(r/subscribe [:state [:hidden :footer]]))
                  "The real event's footer state was accepted")
              (await! (support/wait-for!
                        #(and (= "var(--space)" (.getPropertyValue style "--header-height-current"))
                              (= "calc(var(--space) + 2 * var(--line-width))"
                                 (.getPropertyValue style "--footer-height-current")))
                        "The real shell event's document effects did not run"))
              ;; Do not release the pending owner to make this assertion pass.
              ;; A failed independence check still proceeds to the positive control.
              (let [committed-before-release?
                    (await! (-> (support/wait-for! hidden? "Shell classes waited for an unrelated SSR sibling")
                                (.then (fn [_] true))
                                (.catch (fn [_] false))))]
                (is committed-before-release?
                    (str "Accepted hidden state/CSS must reach native shell classes while the module gate is closed; "
                         "header=" (.-className header) ", footer=" (.-className footer)))
                (is (false? @*sibling-committed?) "The discriminating assertion precedes sibling hydration"))
              ((:release! acquisition) module)
              (await! (support/wait-for! #(and @*sibling-committed? (hidden?)
                                              (nil? (.querySelector element "template[data-module-boundary]")))
                                        "Releasing this module did not complete native hydration"))
              (is (= "true" (.getAttribute footer "aria-hidden")))
              (is (.hasAttribute footer "inert"))
              (is (identical? header (.querySelector element "header"))))
            (.then (fn [_]
                     (is (identical? footer (.querySelector element "#footer-sticky")))
                     (is (identical? sibling (.querySelector element "[data-deferred-shell-sibling]")))
                     (is (empty? @*errors) (pr-str @*errors))))
            (.catch #(is false (str %)))
            (.finally (fn []
                        (when @*root (r/unmount @*root))
                        (.remove element)
                        ((:restore! acquisition))
                        (r/clear-subscription-cache!)
                        (restore-db!)
                        (reset! restore/*context before-context)
                        (reset! context/*interactive? before-interactive?)
                        (reset! validation/*enabled? before-validation?)
                        (r/set-batching-flush! before-flush)
                        (doseq [[property [value priority]] before-css]
                          (if (seq value)
                            (.setProperty style property value priority)
                            (.removeProperty style property)))
                        (done))))))))
