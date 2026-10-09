(ns tolgraven.service-status-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.dom.client :as dom]
            [re-frame.core :as rf]
            [tolgraven.service-status :as status]
            [tolgraven.components.service-status :as notices]))

(deftest notice-renders-and-retry-clears-it-on-recovery
  (async done
    (let [container (.createElement js/document "div") root (dom/create-root container)
          before @status/*failures events (atom []) dispatch rf/dispatch]
      (reset! status/*failures {})
      (.appendChild (.-body js/document) container)
      (set! rf/dispatch #(swap! events conj %))
      (status/fail! :test "Strapi unavailable" "Please retry loading content." #(status/recover! :test))
      (dom/render root [notices/<notices>])
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
              (dom/unmount root) (.remove container)
              (reset! status/*failures before) (set! rf/dispatch dispatch) (done)) 50)
           (catch :default error
             (is false (str error))
             (dom/unmount root) (.remove container)
             (reset! status/*failures before) (set! rf/dispatch dispatch) (done)))) 50))))
