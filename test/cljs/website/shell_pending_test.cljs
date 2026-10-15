(ns website.shell-pending-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [async deftest is]]
            [website.components.shell :as shell]
            [website.events]
            [website.modules.user.subs]
            [website.react :as r]
            [website.render-context :as context]
            [website.subs]
            [website.test-support :as support]))

(deftest pending-header-controls-retain-their-native-nodes
  (async done
    (let [restore! (r/make-restore-fn)
          interactive? @context/*interactive?
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (reset! context/*interactive? true)
      (doseq [module [:search :user]]
        (r/dispatch-sync [:component-data/remove [:state :init :scope module]]))
      (r/dispatch-sync [:state [:active-user] nil])
      (r/dispatch-sync [:state [:menu] false])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [shell/<header> {:menu {:work [], :personal []}}]))
              (await! (support/wait-for! #(.querySelector element ".search-ui-btn img")))
              (let [search (.querySelector element ".search-ui-btn")
                    image (.querySelector search "img")
                    account (.querySelector element ".user-btn[aria-label='User account']")]
                (is (some? account))
                (doseq [open? [true false true]]
                  (r/dispatch-sync [:state [:menu] open?])
                  (await! (support/settle!))
                  (is (identical? search (.querySelector element ".search-ui-btn"))
                      "Header updates retain the pending Search control")
                  (is (identical? image (.querySelector element ".search-ui-btn img"))
                      "The Search image and its readiness owner are retained")
                  (is (identical? account (.querySelector element ".user-btn[aria-label='User account']"))
                      "The pending account link keeps its subscription owner")))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (restore!)
                      (reset! context/*interactive? interactive?)
                      (done)))))))
