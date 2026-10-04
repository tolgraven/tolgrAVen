(ns tolgraven.blog-ssr-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [react :as react]
            [tolgraven.blog.ssr-view :as view]))

(r/defc <hydration-check> [snapshot on-commit]
  (let [[ready? set-ready!] (react/useState false)]
    (react/useEffect (fn [] (set-ready! true) js/undefined) #js [])
    (react/useEffect (fn [] (when ready? (on-commit)) js/undefined) #js [ready?])
    [view/<page> snapshot (when ready? (fn [_] [:p "Client comments ready"]))]))

(deftest node-markup-hydrates-without-replacing-posts-or-showing-a-spinner
  (async done
    (let [element (.createElement js/document "div") *root (atom nil) *errors (atom [])]
      (.appendChild (.-body js/document) element)
      (-> (js/fetch "/js/blog-ssr.json")
          (.then (fn [response]
                   (when-not (.-ok response) (throw (js/Error. "Run python3 scripts/test-blog-ssr.py first")))
                   (.json response)))
          (.then (fn [payload]
                   (let [{:keys [snapshot html]} (js->clj payload :keywordize-keys true)]
                     (set! (.-innerHTML element) html)
                     (let [article (.querySelector element ".blog-post")
                           paragraph (.querySelector element ".blog-post-text p")]
                       (is (some? article) "Server article exists before hydration")
                       (js/Promise.
                        (fn [resolve _]
                          (reset! *root
                            (dom/hydrate-root
                             element
                             [<hydration-check> snapshot
                              (fn []
                                (is (identical? article (.querySelector element ".blog-post")))
                                (is (identical? paragraph (.querySelector element ".blog-post-text p")))
                                (is (nil? (.querySelector element ".loading-spinner")))
                                (is (nil? (.querySelector element ".animate")))
                                (is (empty? @*errors) (pr-str @*errors))
                                (resolve nil))]
                             {:on-recoverable-error #(swap! *errors conj (str %))}))))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (when @*root (dom/unmount @*root)) (.remove element) (done)))))))
