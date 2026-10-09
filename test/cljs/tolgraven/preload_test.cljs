(ns tolgraven.preload-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [tolgraven.test-support :as support]
            [tolgraven.browser-resources :as resources]
            [tolgraven.navigation.preload :as preload]))

(deftest speculation-respects-data-saver-and-very-slow-connections
  (is (preload/connection-allowed? {}))
  (is (preload/connection-allowed? {:effective-type "4g"}))
  (is (not (preload/connection-allowed? {:save-data? true :effective-type "4g"})))
  (is (not (preload/connection-allowed? {:effective-type "2g"})))
  (is (not (preload/connection-allowed? {:effective-type "slow-2g"}))))

(deftest idle-prefetch-is-bounded-and-intent-listeners-follow-the-host-lifecycle
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                fixture (.createElement js/document "div")
                original preload/enqueue!
                after-page! resources/after-page!
                *calls (atom [])
                *idle (atom nil)
                idle (js/Promise. (fn [resolve _] (reset! *idle resolve)))
                root (await! (support/create-root! element))]
            ;; Test-owned DOM represents ordinary page links, not application state.
            (set! (.-innerHTML fixture)
                  "<nav><a href='/blog'><span>Blog</span></a><a href='/test'>Test</a></nav><main><a rel='next' href='/cv'>Next</a><a href='/docs'>Docs</a><a rel='prev' href='/chat' style='position:absolute;top:200vh'>Offscreen</a><a rel='next' href='/gpt' hidden>Hidden</a></main>")
            (set! (.-cssText (.-style fixture)) "position:fixed;top:0;left:0")
            (.appendChild (.-body js/document) fixture)
            ;; Shared after-hydration/paint timing has its own adapter tests.
            (set! resources/after-page! (fn [start!] (start!) (fn [])))
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
                (set! resources/after-page! after-page!)
                (set! preload/enqueue! original)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
