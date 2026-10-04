(ns tolgraven.blog-ssr-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [react :as react]
            [react-dom :as react-dom]
            [tolgraven.ssr.client :as client]
            [tolgraven.ssr.views :as view]))

(defn <header> [_] [:header [:button "Interactive settings"]])
(defn <footer> [_] [:footer "Interactive footer"])
(defn <comments> [_] [:p "Client comments ready"])

(r/defc <hydration-check> [snapshot on-commit *contact]
  (let [[ready? set-ready!] (react/useState false)
        [selected set-selected!] (react/useState nil)]
    (react/useEffect (fn [] (set-ready! true) js/undefined) #js [])
    (react/useEffect (fn [] (when ready? (on-commit)) js/undefined) #js [ready?])
    [view/<page> snapshot
     (when ready? {:comments <comments> :header <header> :footer <footer>
                   :contact! #(swap! *contact inc) :service! set-selected! :selected selected})]))

(defn check-hydration! [fixture selectors done]
  (let [element (.createElement js/document "div") *root (atom nil)
        *errors (atom []) *contact (atom 0)]
    (.appendChild (.-body js/document) element)
    (-> (js/fetch (str "/js/" fixture "-ssr.json"))
        (.then (fn [response]
                 (when-not (.-ok response) (throw (js/Error. "Run python3 scripts/test-blog-ssr.py first")))
                 (.json response)))
        (.then (fn [payload]
                 (let [{:keys [snapshot html]} (js->clj payload :keywordize-keys true)]
                   (set! (.-innerHTML element) html)
                   (let [nodes (mapv #(.querySelector element %) selectors)]
                     (is (every? some? nodes) "Server content exists before hydration")
                     (js/Promise.
                      (fn [resolve _]
                        (reset! *root
                          (dom/hydrate-root
                           element
                           [<hydration-check> snapshot
                            (fn []
                              (doseq [[node selector] (map vector nodes selectors)]
                                (is (identical? node (.querySelector element selector)) selector))
                              (is (nil? (.querySelector element ".loading-spinner")))
                              (is (nil? (.querySelector element ".animate")))
                              (is (empty? @*errors) (pr-str @*errors))
                              (is (= "Interactive settings" (.-textContent (.querySelector element "header button"))))
                              (when (= fixture "landing")
                                (.click (.querySelector element "a[href^='mailto:'].ssr-action"))
                                (is (= 1 @*contact) "Hydrated contact action works on original link"))
                              (resolve nil)) *contact]
                           {:on-recoverable-error #(swap! *errors conj (str %))}))))))))
        (.catch #(is false (str %)))
        (.finally (fn [] (when @*root (dom/unmount @*root)) (.remove element) (done))))))

(deftest node-blog-markup-hydrates-without-replacing-posts
  (async done (check-hydration! "blog" ["main" ".blog-post" ".blog-post-text p"] done)))

(deftest node-landing-markup-hydrates-without-replacing-hero-or-body
  (async done (check-hydration! "landing" ["main" "#intro h1" "#top-banner" "#about" "#gallery"] done)))

(deftest viewport-islands-defer-mount-and-dispose-observers
  (let [original js/IntersectionObserver *callback (atom nil) *observer (atom nil)
        *disconnects (atom 0) *mounts (atom 0)
        element (.createElement js/document "div") root (dom/create-root element)
        view (fn [id] (swap! *mounts inc) [:p (str "Loaded " (name id))])]
    (set! js/IntersectionObserver
          (fn [callback options]
            (is (= "800px" (.-rootMargin options)))
            (reset! *callback callback)
            (reset! *observer #js {:observe (fn [_]) :disconnect #(swap! *disconnects inc)})))
    (try
      (react-dom/flushSync #(.render root (r/as-element [client/<island> :chat view])))
      (is (zero? @*mounts) "No module mounts merely because the page hydrated")
      (react-dom/flushSync #(@*callback #js [#js {:isIntersecting true}] @*observer))
      (is (= "Loaded chat" (.-textContent element)))
      (is (= 1 @*mounts))
      (is (= 1 @*disconnects))
      (react-dom/flushSync #(dom/unmount root))
      (is (= 2 @*disconnects) "Unmount releases the observer too")
      (finally (set! js/IntersectionObserver original)))))
