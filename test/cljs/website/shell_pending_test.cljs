(ns website.shell-pending-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [async deftest is]]
            [website.components.shell :as shell]
            [website.events]
            [website.module-transport :as transport]
            [website.navigation.routes :as routes]
            [reitit.core :as reitit]
            [website.modules.search.module :as search-module]
            [website.modules.user.module :as user-module]
            [website.modules.user.subs]
            [website.react :as r]
            [website.render-context :as context]
            [website.subs]
            [website.test-support :as support]))

(deftest pending-header-controls-retain-their-native-nodes
  (async done
    (let [restore! (r/make-restore-fn)
          interactive? @context/*interactive?
          href context/*href*
          acquisition (transport/install! {:search search-module/spec, :user user-module/spec})
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (reset! context/*interactive? true)
      ;; Native href subscriptions need the real router, but this isolated test
      ;; never starts the application's document/history listener owner.
      (set! context/*href*
        (fn [name params query]
          (some-> (reitit/match-by-name routes/router name params) (reitit/match->path query))))
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              ;; Drain older queued completions before installing this case's
              ;; controlled pending state. No intent releases these native gates.
              (doseq [module [:search :user]
                      path [[:state :init :scope module]
                            [:loader :requested module]
                            [:loader :code-ready module]
                            [:loader :errors module]]]
                (r/dispatch-sync [:component-data/remove path]))
              (r/dispatch-sync [:set [:common/route] (reitit/match-by-path routes/router "/")])
              (r/dispatch-sync [:state [:active-user] nil])
              (r/dispatch-sync [:state [:menu] false])
              (await! (support/render! root [shell/<header> {:menu {:work [], :personal []}}]))
              (await! (support/wait-for! #(.querySelector element ".search-ui-btn img")))
              (let [search (.querySelector element ".search-ui-btn")
                    image (.querySelector search "img")
                    account (.querySelector element ".user-btn[aria-label='User account']")]
                (is (some? account))
                (is (.querySelector element "header a[href='/']") "Native links use the actual home route")
                (is (nil? (.querySelector element "[role=alert]")))
                (is (nil? (.getAttribute search "aria-expanded")) "Exercise the pending Search owner")
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
                      ((:restore! acquisition))
                      (restore!)
                      (reset! context/*interactive? interactive?)
                      (set! context/*href* href)
                      (done)))))))

(deftest bottom-footer-handover-hides-sticky-content-and-restores-the-same-nodes
  (async done
    (let [restore! (r/make-restore-fn)
          element (.createElement js/document "div")
          *root (atom nil)
          style (.-style (.-documentElement js/document))
          property "--footer-height-current"
          value (.getPropertyValue style property)
          priority (.getPropertyPriority style property)]
      (r/dispatch-sync [:state [:hidden :footer] false])
      (r/dispatch-sync [:scroll/at-bottom false])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root
                        [shell/<footer> [{:id "handover", :title "Native footer content"}]]))
              (let [footer (.querySelector element "#footer-sticky")
                    content (.querySelector footer ".footer-content")
                    title (.querySelector content "h2")]
                (is (not (.contains (.-classList footer) "hide")))
                (is (not (.hasAttribute footer "inert")))
                (r/dispatch-sync [:scroll/at-bottom true])
                (await! (support/wait-for! #(and (.contains (.-classList footer) "bottomed")
                                                 (= "0rem" (.getPropertyValue style property)))))
                (is (false? @(r/subscribe [:state [:hidden :footer]]))
                    "Bottom handover hides content independently of scroll-direction hiding")
                (is (.contains (.-classList footer) "hide")
                    "The native hide selector fades the sticky footer's overflowing children")
                (is (= "true" (.getAttribute footer "aria-hidden")))
                (is (.hasAttribute footer "inert"))
                (r/dispatch-sync [:scroll/at-bottom false])
                (await! (support/wait-for! #(and (not (.contains (.-classList footer) "bottomed"))
                                                 (not= "0rem" (.getPropertyValue style property)))))
                (is (not (.contains (.-classList footer) "hide")))
                (is (nil? (.getAttribute footer "aria-hidden")))
                (is (not (.hasAttribute footer "inert")))
                (is (identical? footer (.querySelector element "#footer-sticky")))
                (is (identical? content (.querySelector footer ".footer-content")))
                (is (identical? title (.querySelector footer "h2"))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (restore!)
                      (if (seq value)
                        (.setProperty style property value priority)
                        (.removeProperty style property))
                      (done)))))))
