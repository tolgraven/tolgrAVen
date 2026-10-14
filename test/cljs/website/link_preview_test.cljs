(ns website.link-preview-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [website.test-support :as support]
            [cljs.test :refer-macros [async deftest is]]
            [website.react :as r]



            [website.modules.link-preview.module :as preview-module]
            [website.modules.markdown.module :as markdown-module]
            [website.module-transport :as transport]
            [website.link-preview-fixture]
            [website.modules.link-preview.views :as preview]
            [website.modules.link-preview.interface :as interface]
            [website.component.data :as data]
            [website.loader :as loader]
            [shadow.lazy :as lazy]
            [website.macros :refer-macros [defc]]))
(defn- tick [ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))
(defn- render! [root form] (support/render! root form))
(defn- flush! [] (support/settle!))
(defc <link-example> {:features [:props :links]} [spec url] [:div [:a {:href url} "Example link"]])
(deftest optional-preview-arrival-keeps-content-and-only-acquires-for-candidates
  (async done
    (let [restore! (r/make-restore-fn)
          modules loader/modules
          load-code! loader/load-code!
          ready? loader/ready?
          *ready? (atom false)
          *requests (atom [])
          *cleanups (atom 0)
          *root (atom nil)
          element (.createElement js/document "div")
          spec {:link-container
                {:handle! preview/handle-container-event!
                 :observe! (fn [& args]
                             (let [stop! (apply preview/observe-container! args)]
                               (fn []
                                 (swap! *cleanups inc)
                                 (when stop! (stop!)))))}}
          loadable (reify lazy/ILoadable (ready? [_] @*ready?)
                         IDeref (-deref [_] spec))
          form (fn [text]
                 [interface/<link-container>
                  {:id "optional-preview", :text text, :trust :untrusted}
                  [:p [:a {:href text} "Stable authored content"]]])]
      (reset! r/*app-db (assoc @r/*app-db :loader {}))
      (set! loader/modules {:link-preview loadable})
      (set! loader/ready? (fn [id] (and (= :link-preview id) @*ready?)))
      (set! loader/load-code!
            (fn [options]
              (swap! *requests conj (:module options))
              (js/Promise.resolve nil)))
      (.appendChild js/document.body element)
      (-> (go-promise
            (let [root (await! (support/create-root! element))]
              (reset! *root root)
              (await! (render! root (form "/local-page")))
              (await! (flush!))
              (is (empty? @*requests) "Plain/internal content acquires no preview module")
              (let [container (.-firstElementChild element)
                    anchor (.querySelector element "a")]
                (await! (render! root (form "https://example.invalid/first")))
                (await! (flush!))
                (is (= [:link-preview] @*requests))
                (is (identical? container (.-firstElementChild element)))
                (is (identical? anchor (.querySelector element "a")))
                (is (nil? (get @preview/*containers "optional-preview")))
                (reset! *ready? true)
                (r/dispatch-sync [:loader/code-ready :link-preview])
                (await! (flush!))
                (is (identical? anchor (.querySelector element "a"))
                    "Optional code arrival must not remount authored content")
                (is (= 1 (count (get-in @preview/*containers
                                       ["optional-preview" :candidates-by-element]))))
                (await! (render! root (form "https://example.invalid/second")))
                (await! (flush!))
                (is (= 1 @*cleanups) "Changed candidates release their native owner")
                (is (= "https://example.invalid/second"
                       (-> @preview/*containers (get "optional-preview")
                           :candidates-by-element vals first :url)))
                (is (identical? anchor (.querySelector element "a")))
                (await! (render! root (form "/local-page")))
                (await! (flush!))
                (is (= 2 @*cleanups))
                (is (nil? (get @preview/*containers "optional-preview")))
                (is (= [:link-preview] @*requests) "Ready code is not reacquired")
                (is (identical? container (.-firstElementChild element))))))
          (.catch (fn [error] (is false (str error))))
          (.finally
            (fn []
              (when @*root (support/unmount! @*root))
              (.remove element)
              (set! loader/modules modules)
              (set! loader/load-code! load-code!)
              (set! loader/ready? ready?)
              (r/clear-subscription-cache!)
              (restore!)
              (done)))))))

(deftest preview-ready-before-real-markdown-still-discovers-committed-links
  (async done
    (let [restore! (r/make-restore-fn)
          controlled (transport/install! {:link-preview preview-module/spec
                                          :markdown markdown-module/spec})
          element (.createElement js/document "div")
          *root (atom nil)
          url "https://example.invalid/delayed-parser"
          id "preview-before-parser"]
      (reset! r/*app-db (assoc @r/*app-db :loader {}))
      (.appendChild js/document.body element)
      (-> (go-promise
            (reset! *root (await! (support/create-root! element)))
            (await! (render! @*root [interface/<md> (str "[Delayed link](" url ")")
                                   {:id id, :trust :untrusted}]))
            (await! (support/wait-for! #(every? @(:started controlled) [:markdown :link-preview])))
            ((:release! controlled) :link-preview)
            (await! (support/wait-for! #(contains? @preview/*containers id)))
            (is (nil? (.querySelector element "a")) "Real parser is still gated")
            (is (empty? (get-in @preview/*containers [id :candidates-by-element])))
            (let [container (.querySelector element "[data-link-container]")]
              ((:release! controlled) :markdown)
              (await! (support/wait-for! #(= 1 (count (get-in @preview/*containers
                                                             [id :candidates-by-element])))))
              (is (identical? container (.querySelector element "[data-link-container]")))
              (let [anchor (.querySelector element "a")]
                (.dispatchEvent anchor (js/PointerEvent. "pointerover"
                                         #js {:bubbles true :pointerType "mouse"}))
                (await! (support/wait-for! #(= url (get-in @r/*app-db
                                                          [:state :link-preview :active :url]))))
                (is (identical? anchor (.querySelector element "a")))))
            (support/unmount! @*root)
            (reset! *root nil)
            (is (nil? (get @preview/*containers id)) "Unmount disconnects both observers"))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (.remove element)
                      ((:restore! controlled))
                      (r/clear-subscription-cache!)
                      (restore!)
                      (done)))))))

(deftest queued-prefetch-lifecycle
  (let [before @r/*app-db
        data
          {:container-id "test", :candidate-id 0, :url "https://example.invalid/a", :trust :user}]
    (try (r/dispatch-sync [:link-preview/visible (assoc data :trust :untrusted)])
         (is (empty? (get-in @r/*app-db [:state :link-preview :prefetch-queue])))
         (r/dispatch-sync [:link-preview/visible data])
         (r/dispatch-sync [:link-preview/visible data])
         (is (= [data] (get-in @r/*app-db [:state :link-preview :prefetch-queue])))
         (r/dispatch-sync [:link-preview/unregister "test"])
         (is (nil? (get-in @r/*app-db [:state :link-preview :prefetch (:url data)])))
         (r/dispatch-sync [:link-preview/visible data])
         (is (= [data] (get-in @r/*app-db [:state :link-preview :prefetch-queue])))
         (r/dispatch-sync [:link-preview/close])
         (r/dispatch-sync [:link-preview/status :preview])
         (is (nil? (get-in @r/*app-db [:state :link-preview :active])))
         (finally (reset! r/*app-db before)))))
(deftest defc-keeps-root-ref-and-updates-link-discovery
  (async
    done
    (->
      (go-promise
        (let [element (.createElement js/document "div")
              _ (.appendChild js/document.body element)
              root (await! (support/create-root! element))
              *ref (atom nil)
              *pointer-events (atom 0)
              ref! #(reset! *ref %)
              form (fn [url] [<link-example>
                              {:links {:id "macro-test", :text url, :trust :untrusted},
                               :props {:ref ref!
                                       :on-pointer-over #(swap! *pointer-events inc)}} url])]
          (await! (render! root (form "https://example.invalid/one")))
          (-> (tick 0)
              (.then (fn []
                       (go-promise (is (= (.-firstElementChild element) @*ref))
                                   (.dispatchEvent @*ref
                                     (js/PointerEvent. "pointerover" #js {:bubbles true}))
                                   (is (= 1 @*pointer-events) "Reagent preview props preserve caller events")
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
              active #(get-in @r/*app-db [:state :link-preview :active])]
          (preview/register-provider! "example.invalid" (fn [_ _] [:p "Local preview provider"]))
          (await! (render! root
                           [:<>
                            [interface/<link-container>
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
    (let [restore! (r/make-restore-fn)
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
                (r/dispatch-sync [:link-preview/open data])
                (await! (render! root
                                [:<>
                                 [interface/<link-container>
                                  {:id "visited-test" :text url :trust :untrusted}
                                  [:a {:href url :ref #(reset! *link %)} "Visited link"]]
                                 [preview/<link-preview>]]))
                (await! (flush!))
                (is (nil? (get-in @r/*app-db [:state :link-preview :active])))
                (is (contains? (get-in @r/*app-db [:state :link-preview :visited]) url))
                (is (nil? (.getItem js/sessionStorage storage-key)))
                (.dispatchEvent @*link (js/PointerEvent. "pointerover"
                                        #js {:bubbles true :pointerType "mouse"}))
                (.dispatchEvent @*link (js/FocusEvent. "focusin" #js {:bubbles true}))
                (await! (tick 350))
                (await! (flush!))
                (is (nil? (get-in @r/*app-db [:state :link-preview :active])))
                (is (nil? (.querySelector js/document "[data-popover]")))
                ;; A frozen document's pageshow also closes transient presentation.
                (r/dispatch-sync [:link-preview/open (assoc data :url "https://unvisited.invalid/read")])
                (await! (flush!))
                (.dispatchEvent js/window (js/PageTransitionEvent. "pageshow" #js {:persisted true}))
                (await! (flush!))
                (is (nil? (get-in @r/*app-db [:state :link-preview :active])))
                (finally
                  (support/unmount! root)
                  (.remove element)))))
          (.catch (fn [error] (is false (str error))))
          (.finally (fn []
                      (if old-token (.setItem js/sessionStorage storage-key old-token)
                          (.removeItem js/sessionStorage storage-key))
                      (restore!)
                      (done)))))))

(deftest readable-preview-renders-text-and-respects-frame-policy
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                _ (.appendChild js/document.body element)
                root (await! (support/create-root! element))
                *loaded (atom 0)
                candidate {:url "https://example.invalid/article" :trust :untrusted}
                result {:status "ready"
                        :url (:url candidate)
                        :title "Readable title"
                        :description "Summary"
                        :image "https://example.invalid/image.webp"
                        :blocks [{:kind "heading" :level 1 :text "A heading"}
                                 {:kind "paragraph" :text "<script>plain text</script>"}]
                        :truncated? true
                        :frame-policy "same-origin"}]
            (try
              (is (false? (preview/frame-allowed? result "http://127.0.0.1:4011/blog")))
              (is (preview/frame-allowed? result "https://example.invalid/other"))
              (is (false? (preview/frame-allowed? (assoc result :frame-policy "blocked")
                                                  "https://example.invalid/other")))
              (await! (render! root [preview/<readable-content> result candidate #(swap! *loaded inc)]))
              (await! (tick 70))
              (is (= "A heading" (.-textContent (.querySelector element "h2:last-of-type"))))
              (is (nil? (.querySelector element "script")))
              (is (.includes (.-textContent element) "<script>plain text</script>"))
              (is (= "https://example.invalid/image.webp" (.-src (.querySelector element "img"))))
              (is (nil? (.querySelector element "iframe")))
              (is (= 1 @*loaded))
              (await! (render! root [preview/<readable-content>
                                    (assoc result :frame-policy "none") candidate #(swap! *loaded inc)]))
              (await! (tick 80))
              (is (= (:url candidate) (.-src (.querySelector element "iframe"))))
              (await! (render! root [preview/<readable-content>
                                    (assoc result :blocks [] :frame-policy "blocked")
                                    candidate #(swap! *loaded inc)]))
              (is (.querySelector element ".link-preview__readable--metadata"))
              (is (.includes (.-textContent element) "Only page metadata is available"))
              (is (nil? (.querySelector element "iframe")))
              (finally (await! (support/unmount! root)) (.remove element)))))
        (.then (fn [_] (done)))
        (.catch (fn [error] (is false (str error)) (done))))))
