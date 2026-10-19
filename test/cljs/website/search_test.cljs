(ns website.search-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.registrar :as registrar]
            [website.react :as r]
            [website.events]
            [website.subs]
            [website.modules.search.events]
            [website.modules.search.subs]
            [website.modules.search.views :as views]
            [website.modules.search.presentation :as presentation]
            [website.modules.search.model :as model]
            [website.test-support :as support]))

(defn response [query]
  {:query query
   :found 0
   :page 1
   :hits []
   :suggestions [(str query " example") (str query " guide")]})

(deftest search-releases-focus-on-close-and-cancels-pending-focus
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                *open? (r/atom true)
                scroll-y (.-scrollY js/window)]
            (.appendChild (.-body js/document) container)
            (let [root (await! (support/create-root! container))]
              (try
                (await! (support/render! root
                          [(fn [] [views/<box> {:id "search-input", :open? @*open?}])]))
                (await! (support/wait-for! #(.querySelector container "#search-input")))
                (let [input (.querySelector container "#search-input")]
                  (await! (support/wait-for! #(identical? input (.-activeElement js/document))))
                  (is (identical? input (.-activeElement js/document)))
                  (reset! *open? false)
                  (await! (support/settle!))
                  (is (not (identical? input (.-activeElement js/document)))
                      "Collapsed Search must not keep a hidden caret focused")
                  (reset! *open? true)
                  (await! (support/settle!))
                  (reset! *open? false)
                  (await! (support/settle!))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 150))))
                  (is (not (identical? input (.-activeElement js/document)))
                      "Closing before delayed focus cancels it"))
                (finally
                  (support/unmount! root)
                  (.remove container)
                  (.scrollTo js/window #js {:top scroll-y, :behavior "instant"}))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest debounced-search-rejects-stale-results-and-closing-cancels-queued-work
  (async done
    (let [restore! (r/make-restore-fn)
          requests (atom [])
          delayed (atom [])
          ;; Preserve the actual registered adapters; replacements own only this test.
          original-http (registrar/get-handler :fx :http-xhrio)
          original-delay (registrar/get-handler :fx :dispatch-later)]
      (r/reg-fx :http-xhrio #(swap! requests conj %))
      (r/reg-fx :dispatch-later #(swap! delayed into %))
      (-> (go-promise
            (r/dispatch-sync [:search/init])
            (r/dispatch-sync [:search/query "old"])
            (r/dispatch-sync [:search/query "current"])
            (doseq [{:keys [dispatch]} @delayed] (r/dispatch-sync dispatch))
            (await! (support/settle!))
            (is (= 1 (count @requests)))
            (is (= "current" (get-in (first @requests) [:url-params :q])))
            (r/dispatch-sync [:search/query "newer"])
            (r/dispatch-sync (conj (:on-success (first @requests)) (response "current")))
            (await! (support/settle!))
            (is (nil? (await! (support/subscription-value! [:search/response]))))
            (r/dispatch-sync (:dispatch (last @delayed)))
            (await! (support/settle!))
            (r/dispatch-sync (conj (:on-success (last @requests)) (response "newer")))
            (await! (support/settle!))
            (is (= "newer" (:query (await! (support/subscription-value! [:search/response])))))
            (r/dispatch-sync [:search/query "bad"])
            (r/dispatch-sync [:search/received (get-in @r/*app-db [:search :generation]) "bad"
                              {:query "bad", :found 1, :page 1, :hits [{:preview 42}], :suggestions []}])
            (is (string? (await! (support/subscription-value! [:search/error]))))
            (is (false? (await! (support/subscription-value! [:search/pending?]))))
            (r/dispatch-sync [:search/query "cancelled"])
            (r/dispatch-sync [:search/state [:open?] false])
            (r/dispatch-sync (:dispatch (last @delayed)))
            (await! (support/settle!))
            (is (= 2 (count @requests)))
            (is (false? (await! (support/subscription-value! [:search/pending?])))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (r/reg-fx :http-xhrio original-http)
                      (r/reg-fx :dispatch-later original-delay)
                      (restore!)
                      (done)))))))

(deftest marks-render-as-text-and-never-create-author-html
  (async done
    (let [element (.createElement js/document "div")
          root (atom nil)]
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (let [mounted (await! (support/create-root! element))]
              (reset! root mounted)
              (await! (support/render! mounted [presentation/<fragments>
                                              (model/marked-segments "A <mark><script>bad</script></mark> result")]))
              (is (= "<script>bad</script>" (.-textContent (.querySelector element "mark"))))
              (is (nil? (.querySelector element "script")))) )
          (.catch #(is false (str %)))
          (.finally #(do (when @root (support/unmount! @root)) (.remove element) (done)))))))

(deftest styled-search-is-controlled-and-keyboard-completion-updates-its-real-input
  (async done
    (let [restore! (r/make-restore-fn)
          original-delay (registrar/get-handler :fx :dispatch-later)
          element (.createElement js/document "div")
          root (atom nil)]
      (.appendChild (.-body js/document) element)
      (r/reg-fx :dispatch-later (constantly nil))
      (-> (go-promise
            (r/dispatch-sync [:search/init])
            (r/dispatch-sync [:search/query "C++"])
            (let [generation (get-in @r/*app-db [:search :generation])
                  mounted (await! (support/create-root! element))]
              (r/dispatch-sync [:search/received generation "C++" (response "C++")])
              (reset! root mounted)
              (await! (support/render! mounted [views/<box> {:id "search-keyboard-fixture", :open? true, :inline? true}]))
              (await! (support/wait-for! #(.querySelector element "input")))
              (let [input (.querySelector element "input")]
                (is (= "C++" (.-value input)))
                (is (= "combobox" (.getAttribute input "role")))
                (let [setter (.-set (.getOwnPropertyDescriptor js/Object (.-prototype js/HTMLInputElement) "value"))]
                  (doseq [text ["f" "fe" "fea" "feat" "featu" "featur" "feature" "features"]]
                    (.call setter input text)
                    (.dispatchEvent input (js/Event. "input" #js {:bubbles true}))
                    (is (= text (get-in @r/*app-db [:search :query]))
                        "Native typing accepts every character before the next event")))
                (r/dispatch-sync [:search/query "C++"])
                (r/dispatch-sync [:search/received (get-in @r/*app-db [:search :generation]) "C++" (response "C++")])
                (await! (support/settle!))
                (.dispatchEvent input (js/KeyboardEvent. "keydown" #js {:key "ArrowUp", :bubbles true, :cancelable true}))
                (await! (support/settle!))
                (is (= 1 (await! (support/subscription-value! [:search/selected]))))
                (.dispatchEvent input (js/KeyboardEvent. "keydown" #js {:key "Tab", :bubbles true, :cancelable true}))
                (await! (support/settle!))
                (is (= "C++ guide" (.-value input)))
                (is (zero? (.-length (.querySelectorAll element ".appear-wrapper")))))))
          (.catch #(is false (str %)))
          (.finally #(do (when @root (support/unmount! @root)) (.remove element)
                         (r/reg-fx :dispatch-later original-delay) (restore!) (done)))))))

(deftest header-search-retains-its-exiting-input-and-cancels-removal-on-reentry
  (async done
    (let [restore! (r/make-restore-fn)
          element (.createElement js/document "div")
          style (.createElement js/document "style")
          *root (atom nil)]
      (set! (.-id element) "search-presence-fixture")
      ;; A finite native clock exercises presence in the otherwise unstyled runner.
      (set! (.-textContent style)
            "#search-presence-fixture .search-ui {opacity:0;transition:opacity .3s linear} #search-presence-fixture .search-ui.appeared {opacity:1}")
      (.appendChild (.-head js/document) style)
      (.appendChild (.-body js/document) element)
      (r/dispatch-sync [:state [:search :open?] false])
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [views/<ui>]))
              (is (nil? (.querySelector element ".search-ui")))
              (r/dispatch-sync [:state [:search :open?] true])
              (await! (support/wait-for! #(.querySelector element "#search-input")))
              (let [panel (.querySelector element ".search-ui")
                    input (.querySelector element "#search-input")]
                (await! (support/wait-for! #(identical? input (.-activeElement js/document))))
                (await! (support/wait-for! #(.contains (.-classList panel) "appeared")))
                (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 350))))
                (r/dispatch-sync [:state [:search :open?] false])
                (await! (support/wait-for! #(.hasAttribute panel "inert")))
                (is (identical? panel (.querySelector element ".search-ui"))
                    "Presence retains the actual panel during its exit")
                (is (nil? (.querySelector panel ".styled-input-container.closed"))
                    "The input must not change margin/border while its parent collapses")
                (is (not (identical? input (.-activeElement js/document))))
                (r/dispatch-sync [:state [:search :open?] true])
                (await! (support/wait-for! #(not (.hasAttribute panel "inert"))))
                (is (identical? input (.querySelector element "#search-input"))
                    "Reopening during exit keeps the same controlled input")
                (await! (support/wait-for! #(identical? input (.-activeElement js/document))))
                (r/dispatch-sync [:state [:search :open?] false])
                (await! (support/wait-for! #(nil? (.querySelector element ".search-ui"))))
                (is (not (.-isConnected input)) "Completed exit leaves no hidden panel"))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (.remove style)
                      (restore!)
                      (done)))))))

(deftest suggestion-motion-keeps-its-domain-arguments-after-the-instance-spec
  (async done
    (let [restore! (r/make-restore-fn)
          original-delay (registrar/get-handler :fx :dispatch-later)
          element (.createElement js/document "div")
          *root (atom nil)]
      (.appendChild (.-body js/document) element)
      (r/reg-fx :dispatch-later (constantly nil))
      (-> (go-promise
            (r/dispatch-sync [:search/init])
            (r/dispatch-sync [:search/query "native"])
            (r/dispatch-sync [:search/received (get-in @r/*app-db [:search :generation])
                              "native" (response "native")])
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (support/render! root [views/<suggestions> {:id "native-search", :open? true}]))
              (await! (support/wait-for! #(.querySelector element "[role=listbox]")))
              (is (= "native-search-suggestions" (.-id (.querySelector element "[role=listbox]"))))
              (is (= ["native example" "native guide"]
                     (mapv #(.-textContent %) (array-seq (.querySelectorAll element "[role=option]")))))
              (is (= "native-search-suggestions-1" (.-id (.querySelector element "[role=option]:last-child"))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      (r/reg-fx :dispatch-later original-delay)
                      (restore!)
                      (done)))))))
