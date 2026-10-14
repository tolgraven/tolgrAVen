(ns website.boot-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [website.boot :as boot]
            [website.listener :as listener]
            [website.component.storage :as storage]
            [website.component.legacy-storage :as legacy]
            [website.component.restore :as restore]
            [website.browser-resources :as resources]
            [website.test-support :as support]))

(deftest lifecycle-listeners-keep-options-and-release-only-their-acquisition
  (let [target (js/EventTarget.)
        *seen (atom [])
        binding {:id :listener-test
                 :owner :listener-test
                 :target target
                 :event "probe"
                 :handler #(swap! *seen conj :old)}
        old-stop! (listener/register! binding)
        new-stop! (listener/register! (assoc binding :handler #(swap! *seen conj :new)))
        first-stop! (listener/listen! :listener-test
                      [{:target target, :event "once", :handler #(swap! *seen conj :once), :once? true}])
        second-stop! (listener/listen! :listener-test
                       [{:target   target
                         :event    "passive"
                         :handler  #(.preventDefault %)
                         :passive? true}])]
    (try
      (old-stop!)
      (.dispatchEvent target (js/Event. "probe"))
      (.dispatchEvent target (js/Event. "once"))
      (.dispatchEvent target (js/Event. "once"))
      (is (= [:new :once] @*seen))
      (let [event (js/Event. "passive" #js {:cancelable true})]
        (.dispatchEvent target event)
        (is (not (.-defaultPrevented event))))
      (first-stop!)
      (is (= 2 (count (filter #(= :listener-test (:owner %)) (vals @listener/*bindings)))))
      (new-stop!)
      (new-stop!)
      (.dispatchEvent target (js/Event. "probe"))
      (is (= [:new :once] @*seen))
      (finally (old-stop!) (new-stop!) (first-stop!) (second-stop!)))
    (is (not-any? #(= :listener-test (:owner %)) (vals @listener/*bindings)))
    (is (try
          (listener/listen! :listener-test
            [{:target target, :event "partial", :handler identity}
             {:target nil, :event "invalid", :handler identity}])
          false
          (catch :default _ true)))
    (is (not-any? #(= :listener-test (:owner %)) (vals @listener/*bindings)))))

(deftest document-listeners-start-through-events-and-stop-with-their-owner
  (async done
    (-> (go-promise
          (let [restore-db! (r/make-restore-fn)
                old-history (.-scrollRestoration js/history)
                old-context @restore/*context
                *saved (atom [])]
            (r/with-framework-bindings [storage/save-navigation! #(swap! *saved conj :snapshot)
                          legacy/drain! #(swap! *saved conj :legacy)
                          legacy/*state (atom nil)
                          legacy/*loaded? (atom true)
                          legacy/*pending (atom nil)]
              (try
                (is (empty? @listener/*bindings) "Namespace import installed no listeners")
                (r/dispatch-sync [:init/app-db])
                (r/dispatch-sync [:boot/document])
                (await! (support/settle!))
                (let [ids (set (keys @listener/*bindings))]
                  (r/dispatch-sync [:boot/document])
                  (await! (support/settle!))
                  (is (= ids (set (keys @listener/*bindings))))
                  (is (contains? ids :boot/storage)))
                (.dispatchEvent js/window
                  (js/StorageEvent. "storage"
                    #js {:storageArea (.-localStorage js/window)
                         :key legacy/disk-key
                         :newValue (legacy/encode {:cookie-notice-accepted true})}))
                (is (= {:cookie-notice-accepted true} (legacy/read!)))
                (.dispatchEvent js/window (js/Event. "pagehide"))
                (is (= [:snapshot :legacy] @*saved) "One ordered flush, including queued legacy writes")
                (is (= "auto" (.-scrollRestoration js/history)))
                (.dispatchEvent js/window (js/PageTransitionEvent. "pageshow" #js {:persisted true}))
                (is (:back? @restore/*context))
                (r/dispatch-sync [:boot/stop])
                (.dispatchEvent js/window (js/Event. "pagehide"))
                (is (= [:snapshot :legacy] @*saved) "Stopped listeners no longer save")
                (is (empty? @listener/*bindings))
                (finally
                  (r/dispatch-sync [:boot/stop])
                  (reset! restore/*context old-context)
                  (set! (.-scrollRestoration js/history) old-history)
                  (restore-db!))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest committed-boot-host-owns-listeners-and-cancels-background-work-on-unmount
  (async done
    (let [after-page! resources/after-page!
          *queued (atom 0)
          *cancelled (atom 0)
          restore-db! (r/make-restore-fn)]
      ;; Leave deferred downloads at the existing, independently tested boundary.
      (set! resources/after-page!
            (fn [_] (swap! *queued inc) #(swap! *cancelled inc)))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (r/dispatch-sync [:init/app-db])
                (await! (support/render! root [boot/<lifecycle>]))
                (await! (support/settle!))
                (is (contains? @listener/*bindings :boot/resize))
                (is (some #(= :site (:owner %)) (vals @listener/*bindings)))
                (let [binding-count (count @listener/*bindings)]
                  (await! (support/render! root nil))
                  (is (empty? @listener/*bindings))
                  (is (= @*queued @*cancelled) "Unmount cancels every deferred host")
                  (await! (support/render! root [boot/<lifecycle>]))
                  (await! (support/settle!))
                  (is (= binding-count (count @listener/*bindings)) "Remount does not multiply callbacks"))
                (finally (support/unmount! root)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (r/dispatch-sync [:boot/stop])
                      (set! resources/after-page! after-page!)
                      (restore-db!)
                      (done)))))))
