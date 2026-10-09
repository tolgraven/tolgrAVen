(ns tolgraven.boot-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.boot :as boot]
            [tolgraven.listener :as listener]
            [tolgraven.component.storage :as storage]
            [tolgraven.component.legacy-storage :as legacy]
            [tolgraven.component.restore :as restore]
            [tolgraven.browser-resources :as resources]
            [tolgraven.test-support :as support]))

(deftest document-listeners-start-through-events-and-stop-with-their-owner
  (async done
    (-> (go-promise
          (let [restore-db! (re-frame/make-restore-fn)
                old-history (.-scrollRestoration js/history)
                old-context @restore/*context
                *saved (atom [])]
            (with-redefs [storage/save-navigation! #(swap! *saved conj :snapshot)
                          legacy/drain! #(swap! *saved conj :legacy)
                          legacy/*state (atom nil)
                          legacy/*loaded? (atom true)
                          legacy/*pending (atom nil)]
              (try
                (is (empty? @listener/*bindings) "Namespace import installed no listeners")
                (rf/dispatch-sync [:init/app-db])
                (rf/dispatch-sync [:boot/document])
                (await! (support/settle!))
                (let [ids (set (keys @listener/*bindings))]
                  (rf/dispatch-sync [:boot/document])
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
                (rf/dispatch-sync [:boot/stop])
                (.dispatchEvent js/window (js/Event. "pagehide"))
                (is (= [:snapshot :legacy] @*saved) "Stopped listeners no longer save")
                (is (empty? @listener/*bindings))
                (finally
                  (rf/dispatch-sync [:boot/stop])
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
          restore-db! (re-frame/make-restore-fn)]
      ;; Leave deferred downloads at the existing, independently tested boundary.
      (set! resources/after-page!
            (fn [_] (swap! *queued inc) #(swap! *cancelled inc)))
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))]
              (try
                (rf/dispatch-sync [:init/app-db])
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
                      (rf/dispatch-sync [:boot/stop])
                      (set! resources/after-page! after-page!)
                      (restore-db!)
                      (done)))))))
