(ns website.settings-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [async deftest is testing]]
            [website.react :as r]
            [website.component.restore :as restore]
            [website.modules.user.views :as user]
            [website.events]
            [website.subs]
            [website.modules.settings.views :as views]
            [website.test-support :as support]
            [website.modules.settings.events :as settings]))

(deftest appearance-values-retain-native-control-units
  (testing "Line widths remain pixels, including preferences saved in rem"
    (is (= "2px" (settings/css-value "2px" 17.6 "px")))
    (is (= "2px" (settings/css-value "0.125rem" 16 "px")))
    (is (= "2.2px" (settings/css-value "0.125rem" 17.6 "px")))
    (is (= "0px" (settings/css-value "0px" 11.52 "px"))))
  (testing "Spacing keeps its existing rem contract and rounding stays a percentage"
    (is (= "2rem" (settings/css-value "32px" 16)))
    (is (= "0.75rem" (settings/css-value "0.75rem" 11.52 "rem")))
    (is (= "5%" (settings/css-value "5%" 17.6)))))

(deftest theme-toggle-follows-the-document-and-panel-retains-its-exit
  (async done
    (let [restore! (r/make-restore-fn)
          document-root (.-documentElement js/document)
          theme (.getAttribute document-root "data-theme")
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (.setAttribute document-root "data-theme" "light")
      (r/dispatch-sync [:state [:settings :panel-open] true])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [views/<settings>]))
              (await! (support/wait-for!
                        #(= "false" (some-> (.querySelector element "[role=switch]")
                                            (.getAttribute "aria-checked")))))
              (doseq [expected ["dark" "light" "dark"]]
                (.click (.querySelector element "[role=switch]"))
                (await! (support/wait-for! #(= expected (.getAttribute document-root "data-theme"))))
                (await! (support/wait-for!
                          #(= (str (= expected "dark"))
                              (.getAttribute (.querySelector element "[role=switch]") "aria-checked")))))
              (let [panel (.querySelector element ".settings-panel")]
                ;; Give the native root a finite clock even in the unstyled runner.
                (set! (.-transition (.-style panel)) "opacity 0.35s linear")
                (r/dispatch-sync [:state [:settings :panel-open] false])
                (await! (support/settle!))
                (when-not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
                  (is (identical? panel (.querySelector element ".settings-panel")))
                  (is (.hasAttribute panel "inert"))))
              (await! (support/wait-for! #(nil? (.querySelector element ".settings-panel"))))
              (r/dispatch-sync [:state [:settings :panel-open] true])
              (await! (support/wait-for! #(.querySelector element "[role=switch]")))
              (is (= "true" (.getAttribute (.querySelector element "[role=switch]") "aria-checked")))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (if theme (.setAttribute document-root "data-theme" theme)
                          (.removeAttribute document-root "data-theme"))
                      (restore!)
                      (done)))))))

(deftest explicit-menu-panels-enter-after-document-restoration
  (async done
    (-> (go-promise
          (doseq [context [{:back? true} {:hydrate? true}]
                  [component selector open-event close-event]
                  [[views/<settings> ".settings-panel"
                    [:state [:settings :panel-open] true] [:state [:settings :panel-open] false]]
                   [user/<user-section> ".user-section-wrapper"
                    [:user/open-ui :login] [:user/close-ui]]]]
            (let [restore-db! (r/make-restore-fn)
                  previous @restore/*context
                  element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  *initial (atom nil)
                  observer (js/MutationObserver.
                             (fn [_ _]
                               (when-let [panel (.querySelector element selector)]
                                 (when-not @*initial
                                   (reset! *initial {:appeared? (.contains (.-classList panel) "appeared")})))))]
              (try
                (.appendChild (.-body js/document) element)
                (restore/begin! context)
                (restore/hydrated!)
                (r/dispatch-sync [:state [:supabase-init] :ready])
                (r/dispatch-sync close-event)
                (await! (support/render! root [component]))
                (.observe observer element #js {:subtree true, :childList true})
                (r/dispatch-sync open-event)
                (let [panel (await! (support/wait-for! #(.querySelector element selector)))]
                  (when-not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
                    (is (false? (:appeared? @*initial))
                        (str selector " starts its entrance after " context)))
                  (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                  (r/dispatch-sync close-event)
                  (await! (support/wait-for! #(nil? (.querySelector element selector)))))
                (finally
                  (.disconnect observer)
                  (support/unmount! root)
                  (.remove element)
                  (restore-db!)
                  (reset! restore/*context previous))))))
        (.catch #(is false (str %)))
        (.finally done))))
