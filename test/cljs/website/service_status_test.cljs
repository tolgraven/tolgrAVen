(ns website.service-status-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [website.service-status :as status]
            [website.components.service-status :as notices]))

(deftest notice-renders-and-retry-clears-it-on-recovery
  (async done
    (let [container (.createElement js/document "div") root (r/create-root container)
          before @status/*failures events (atom []) dispatch r/dispatch]
      (reset! status/*failures {})
      (.appendChild (.-body js/document) container)
      (r/set-dispatch-function! #(swap! events conj %))
      (status/fail! :test "Strapi unavailable" "Please retry loading content." #(status/recover! :test))
      (r/render root [notices/<notices>])
      (js/setTimeout
       (fn []
         (try
           (is (some? (.querySelector container "[role=alert]")))
           (is (.includes (.-textContent container) "Strapi unavailable"))
           (is (= :diag/new (ffirst @events)))
           (.click (.querySelector container "button"))
           (js/setTimeout
            (fn []
              (is (nil? (.querySelector container "[role=alert]")))
              (r/unmount root) (.remove container)
              (reset! status/*failures before) (r/set-dispatch-function! dispatch) (done)) 50)
           (catch :default error
             (is false (str error))
             (r/unmount root) (.remove container)
             (reset! status/*failures before) (r/set-dispatch-function! dispatch) (done)))) 50))))
