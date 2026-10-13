(ns website.loader-view-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [shadow.lazy :as lazy]
            [website.react :as r]
            [website.loader :as loader]
            [website.loader.code :as code]
            [website.loader.view :as view]
            [website.loader.styles :as styles]
            [website.validation.runtime :as validation]
            [website.render-context :as context]
            [website.test-support :as support]
            [website.events]
            [website.subs]))

(r/defc <loaded> [] [:p {:data-owned-view true} "Ready"])

(r/defc <fixture> [id export]
  (view/form id export [] [:p {:data-pending-view true} "Pending"]))

(deftest fetched-module-waits-for-its-css-and-install-hook
  (async done
    (let [id (keyword (str "lightweight-" (random-uuid)))
          *css-release (atom nil)
          *install-release (atom nil)
          *css-ready? (atom false)
          *install-started? (atom false)
          css (js/Promise. (fn [resolve _] (reset! *css-release resolve)))
          installed (js/Promise. (fn [resolve _] (reset! *install-release resolve)))
          spec {:id id
                :view {:view <loaded>}
                :install (fn [] (reset! *install-started? true) installed)}
          loadable (reify lazy/ILoadable
                     (ready? [_] true)
                     IDeref (-deref [_] spec))
          before {:modules loader/modules
                  :code-modules code/modules
                  :css styles/acquire!
                  :ready? styles/ready?
                  :db @r/*app-db
                  :validation @validation/*enabled?
                  :loads @loader/*loads
                  :code-loads @loader/*code-loads
                  :installed @loader/*installed}
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (reset! validation/*enabled? false)
      (set! loader/modules (assoc loader/modules id loadable))
      (set! code/modules (assoc code/modules id loadable))
      (set! styles/acquire! (fn [module] (if (= id module) css ((:css before) module))))
      (set! styles/ready? (fn [module] (if (= id module) @*css-ready? ((:ready? before) module))))
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [<fixture> id :view]))
              (await! (support/wait-for! #(contains? @loader/*code-loads id)))
              (is (lazy/ready? loadable) "JavaScript is already fetched")
              (is (some? (.querySelector element "[data-pending-view]")) "CSS pending keeps the fallback")
              (is (nil? (.querySelector element "[data-owned-view]")))
              (reset! *css-ready? true)
              (@*css-release nil)
              (await! (support/wait-for! #(deref *install-started?)))
              (await! (support/settle!))
              (is (some? (.querySelector element "[data-pending-view]")) "Completed CSS still waits for installation")
              (is (nil? (.querySelector element "[data-owned-view]")))
              (@*install-release nil)
              (await! (support/wait-for! #(.querySelector element "[data-owned-view]")))
              (is (contains? @loader/*installed id))
              (is (nil? (.querySelector element "[data-pending-view]")))))
          (.catch #(is false (.-message %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (set! loader/modules (:modules before))
                      (set! code/modules (:code-modules before))
                      (set! styles/acquire! (:css before))
                      (set! styles/ready? (:ready? before))
                      (reset! loader/*loads (:loads before))
                      (reset! loader/*code-loads (:code-loads before))
                      (reset! loader/*installed (:installed before))
                      (r/clear-subscription-cache!)
                      (reset! r/*app-db (:db before))
                      (reset! validation/*enabled? (:validation before))
                      (done)))))))

(deftest ssr-records-only-resolved-visible-exports
  (let [*exports (atom {})
        modules {:home {:id :home, :view {:float-image <loaded>}}}
        before @loader/*code-loads
        before-code code/modules
        unselected (reify lazy/ILoadable
                     (ready? [_] true)
                     IDeref (-deref [_] {:id :unselected, :view {:view <loaded>}}))]
    (set! code/modules (assoc code/modules :unselected unselected))
    (try
      (binding [context/*server?* true
                context/*modules* modules
                context/*rendered-views* *exports]
        (let [html (r/render-to-string [<fixture> :home :float-image])]
          (is (.includes html "Ready"))
          (is (= {:home #{:float-image}} @*exports)))
        (r/render-to-string [<fixture> :home :missing])
        (r/render-to-string [<fixture> :unknown :view])
        (let [html (r/render-to-string [<fixture> :unselected :view])]
          (is (.includes html "Pending") "Fetched browser code cannot escape the server's selected inventory")
          (is (not (.includes html "Ready"))))
        (is (= {:home #{:float-image}} @*exports) "Fallbacks do not invent visible owners")
        (is (= before @loader/*code-loads) "Server rendering starts no browser acquisition"))
      (finally (set! code/modules before-code)))))
