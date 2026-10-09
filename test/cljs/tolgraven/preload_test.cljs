(ns tolgraven.preload-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.test-support :as support]
            [tolgraven.navigation.preload :as preload]))

(deftest idle-prefetch-is-bounded-and-intent-listeners-follow-the-host-lifecycle
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                fixture (.createElement js/document "div")
                original preload/enqueue!
                *calls (atom [])
                *idle (atom nil)
                idle (js/Promise. (fn [resolve _] (reset! *idle resolve)))
                root (await! (support/create-root! element))]
            ;; Test-owned DOM represents ordinary page links, not application state.
            (set! (.-innerHTML fixture)
                  "<nav><a href='/blog'><span>Blog</span></a><a href='/test'>Test</a></nav><main><a rel='next' href='/cv'>Next</a><a href='/docs'>Docs</a></main>")
            (.appendChild (.-body js/document) fixture)
            (set! preload/enqueue!
                  (fn [paths]
                    (swap! *calls conj paths)
                    (when (some #{"/cv"} paths) (@*idle nil))))
            (try
              (await! (support/render! root [preload/<background>]))
              (await! idle)
              (is (= [["/cv"]] @*calls) "A global navbar and ordinary links do no idle loading")
              (.dispatchEvent (.querySelector fixture "nav span") (js/Event. "pointerover" #js {:bubbles true}))
              (is (= ["/blog"] (last @*calls)))
              (.dispatchEvent (.querySelector fixture "a[href='/docs']") (js/Event. "focusin" #js {:bubbles true}))
              (is (= ["/docs"] (last @*calls)))
              (await! (support/render! root nil))
              (let [calls @*calls]
                (.dispatchEvent (.querySelector fixture "nav span") (js/Event. "pointerover" #js {:bubbles true}))
                (is (= calls @*calls) "Unmount releases intent listeners"))
              (finally
                (support/unmount! root)
                (.remove fixture)
                (set! preload/enqueue! original)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
