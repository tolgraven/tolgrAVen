(ns tolgraven.oembed-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.events]
            [tolgraven.components.oembed :as oembed]
            [tolgraven.test-support :as support]))

(deftest provider-html-renders-through-reagent-and-empty-html-remains-valid
  (async done
    (let [restore! (re-frame/make-restore-fn)
          *request (atom nil)]
      (rf/reg-fx :http-xhrio #(reset! *request %))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (await! (support/render! root
                         [oembed/<oembed-view> "https://soundcloud.com/example/show"
                          [:p "Loading player"]]))
                (await! (support/wait-for! #(deref *request)))
                (is (= "/api/oembed" (:uri @*request)))
                (is (= "Loading player" (.-textContent element)))
                (rf/dispatch (conj (:on-success @*request)
                                   {:html "<p>Listen to <strong>our show</strong>.</p>"}))
                (await! (support/wait-for! #(.querySelector element ".oembed-inner strong")))
                (is (= "our show" (.-textContent (.querySelector element "strong"))))
                (is (= "Listen to our show." (.-textContent (.querySelector element ".oembed-inner"))))
                (rf/dispatch (conj (:on-success @*request) {}))
                (await! (support/wait-for!
                          #(= "" (.-textContent (.querySelector element ".oembed-inner")))))
                (is (some? (.querySelector element ".oembed-inner")))
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))
