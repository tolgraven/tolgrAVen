(ns tolgraven.link-preview-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [tolgraven.test-support :as support]
            [cljs.test :refer-macros [async deftest is]]
            [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [re-frame.core :as rf]
            [re-frame.db :as db]
            [tolgraven.modules.link-preview.module]
            [tolgraven.link-preview-fixture]
            [tolgraven.modules.link-preview.views :as preview]
            [tolgraven.macros :refer-macros [defc]]))
(defn- tick [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))
(defn- render! [root form] (support/render! root form))
(defn- flush! [] (support/settle!))
(defc <link-example> {:features [:props :links]} [spec url] [:div [:a {:href url} "Example link"]])
(deftest queued-prefetch-lifecycle
  (let [before @db/app-db
        data
          {:container-id "test", :candidate-id 0, :url "https://example.invalid/a", :trust :user}]
    (try (rf/dispatch-sync [:link-preview/visible (assoc data :trust :untrusted)])
         (is (empty? (get-in @db/app-db [:state :link-preview :prefetch-queue])))
         (rf/dispatch-sync [:link-preview/visible data])
         (rf/dispatch-sync [:link-preview/visible data])
         (is (= [data] (get-in @db/app-db [:state :link-preview :prefetch-queue])))
         (rf/dispatch-sync [:link-preview/unregister "test"])
         (is (nil? (get-in @db/app-db [:state :link-preview :prefetch (:url data)])))
         (rf/dispatch-sync [:link-preview/visible data])
         (is (= [data] (get-in @db/app-db [:state :link-preview :prefetch-queue])))
         (rf/dispatch-sync [:link-preview/close])
         (rf/dispatch-sync [:link-preview/status :preview])
         (is (nil? (get-in @db/app-db [:state :link-preview :active])))
         (finally (reset! db/app-db before)))))
(deftest defc-keeps-root-ref-and-updates-link-discovery
  (async
    done
    (->
      (go-promise
        (let [element (.createElement js/document "div")
              _ (.appendChild js/document.body element)
              root (await! (support/create-root! element))
              *ref (atom nil)
              ref! #(reset! *ref %)
              form (fn [url] [<link-example>
                              {:links {:id "macro-test", :text url, :trust :untrusted},
                               :props {:ref ref!}} url])]
          (await! (render! root (form "https://example.invalid/one")))
          (-> (tick 0)
              (.then (fn []
                       (go-promise (is (= (.-firstElementChild element) @*ref))
                                   (is (= 1
                                          (count (get-in @preview/*containers
                                                         ["macro-test" :candidates-by-element]))))
                                   (is (= "https://example.invalid/one"
                                          (-> @preview/*containers
                                              (get "macro-test")
                                              :candidates-by-element
                                              vals
                                              first
                                              :url)))
                                   (await! (render! root (form "https://example.invalid/two")))
                                   (tick 0))))
              (.then (fn []
                       (is (= "https://example.invalid/two"
                              (-> @preview/*containers
                                  (get "macro-test")
                                  :candidates-by-element
                                  vals
                                  first
                                  :url)))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (support/unmount! root)
                          (is (nil? @*ref))
                          (is (nil? (get @preview/*containers "macro-test")))
                          (.remove element)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest preview-interactions
  (async
    done
    (->
      (go-promise
        (let [element (.createElement js/document "div")
              _ (.appendChild js/document.body element)
              root (await! (support/create-root! element))
              url "https://example.invalid/preview"
              *link (atom nil)
              fire! (fn [kind & [props]]
                      (.dispatchEvent @*link
                                      (js/PointerEvent. kind
                                                        (clj->js (merge {:bubbles true,
                                                                         :pointerType "mouse"}
                                                                        props)))))
              active #(get-in @db/app-db [:state :link-preview :active])]
          (preview/register-provider! "example.invalid" (fn [_ _] [:p "Local preview provider"]))
          (await! (render! root
                           [:<>
                            [preview/<link-container>
                             {:id "interaction-test", :text url, :trust :untrusted}
                             [:a {:href url, :ref #(reset! *link %)} "Preview test link"]]
                            [preview/<link-preview>]]))
          (fire! "pointerover")
          (fire! "pointerout")
          (->
            (tick 350)
            (.then (fn []
                     (is (nil? (active)) "Leaving before hover delay cancels opening")
                     (fire! "pointerover")
                     (tick 350)))
            (.then (fn []
                     (go-promise (await! (flush!))
                                 (is (= url (:url (active))))
                                 (is (some? (.querySelector js/document "[data-popover]")))
                                 (.dispatchEvent js/window
                                                 (js/KeyboardEvent. "keydown" #js {:key "Escape"}))
                                 (tick 30))))
            (.then (fn []
                     (go-promise (await! (flush!))
                                 (is (nil? (active)) "Escape dismisses the preview")
                                 (is (nil? (.querySelector js/document "[data-popover]")))
                                 ;; First touch opens without navigating; modified clicks
                                 ;; retain native behavior.
                                 (fire! "pointerdown" {:pointerType "touch"})
                                 (let [event (js/MouseEvent.
                                               "click"
                                               #js {:bubbles true, :cancelable true, :button 0})]
                                   (.dispatchEvent @*link event)
                                   (is (.-defaultPrevented event)))
                                 (tick 30))))
            (.then
              (fn []
                (go-promise
                  (await! (flush!))
                  (is (= url (:url (active))))
                  (let [dialog (.querySelector js/document "[data-popover]")
                        *errors (atom [])
                        on-error #(do (swap! *errors conj (.-message %)) (.preventDefault %))]
                    (.addEventListener js/window "error" on-error)
                    (.dispatchEvent dialog
                                    (js/KeyboardEvent. "keydown" #js {:key "Enter", :bubbles true}))
                    (.removeEventListener js/window "error" on-error)
                    (is (empty? @*errors)
                        "Activation reads reduced-motion preference without throwing")
                    (.dispatchEvent js/window (js/KeyboardEvent. "keydown" #js {:key "Escape"})))
                  (tick 450))))
            (.then (fn []
                     (is (nil? (active)) "Escape also cancels pending animated navigation")
                     (fire! "pointerdown" {:pointerType "touch"})
                     (.dispatchEvent @*link
                                     (js/MouseEvent. "click" #js {:bubbles true, :cancelable true}))
                     (tick 30)))
            (.then (fn []
                     (.dispatchEvent js/document.body
                                     (js/PointerEvent. "pointerdown" #js {:bubbles true}))
                     (tick 30)))
            (.then (fn []
                     (is (nil? (active)) "Outside pointer dismisses touch preview")
                     (fire! "pointerover")
                     (support/unmount! root)
                     (tick 350)))
            (.then (fn []
                     (is (nil? (active)) "Unmount cancels delayed hover")
                     (is (nil? (get @preview/*containers "interaction-test")))))
            (.catch #(is false (str %)))
            (.finally (fn []
                        (when (.-firstChild element) (support/unmount! root))
                        (swap! preview/*preview-providers dissoc "example.invalid")
                        (.remove element)
                        (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))

(deftest returning-from-a-visited-preview-does-not-reopen-on-hover-or-focus
  (async done
    (let [restore! (rf/make-restore-fn)
          storage-key "tolgraven.modules.link-preview.transition"
          old-token (.getItem js/sessionStorage storage-key)]
      (-> (go-promise
            (let [element (.createElement js/document "div")
                  root (await! (support/create-root! element))
                  url "https://visited.invalid/read"
                  data {:url url :origin-page (str (.-pathname js/location) (.-search js/location))
                        :container-id "visited-test" :candidate-id 0 :trust :untrusted}
                  *link (atom nil)]
              (.appendChild js/document.body element)
              (try
                (.setItem js/sessionStorage storage-key (js/JSON.stringify (clj->js data)))
                (rf/dispatch-sync [:link-preview/open data])
                (await! (render! root
                                [:<>
                                 [preview/<link-container>
                                  {:id "visited-test" :text url :trust :untrusted}
                                  [:a {:href url :ref #(reset! *link %)} "Visited link"]]
                                 [preview/<link-preview>]]))
                (await! (flush!))
                (is (nil? (get-in @db/app-db [:state :link-preview :active])))
                (is (contains? (get-in @db/app-db [:state :link-preview :visited]) url))
                (is (nil? (.getItem js/sessionStorage storage-key)))
                (.dispatchEvent @*link (js/PointerEvent. "pointerover"
                                        #js {:bubbles true :pointerType "mouse"}))
                (.dispatchEvent @*link (js/FocusEvent. "focusin" #js {:bubbles true}))
                (await! (tick 350))
                (await! (flush!))
                (is (nil? (get-in @db/app-db [:state :link-preview :active])))
                (is (nil? (.querySelector js/document "[data-popover]")))
                ;; A frozen document's pageshow also closes transient presentation.
                (rf/dispatch-sync [:link-preview/open (assoc data :url "https://unvisited.invalid/read")])
                (await! (flush!))
                (.dispatchEvent js/window (js/PageTransitionEvent. "pageshow" #js {:persisted true}))
                (await! (flush!))
                (is (nil? (get-in @db/app-db [:state :link-preview :active])))
                (finally
                  (support/unmount! root)
                  (.remove element)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (if old-token (.setItem js/sessionStorage storage-key old-token)
                          (.removeItem js/sessionStorage storage-key))
                      (restore!)
                      (done)))))))
