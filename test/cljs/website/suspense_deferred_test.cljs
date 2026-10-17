(ns website.suspense-deferred-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.component.hydration :as hydration]
            [website.component.restore :as restore]
            [website.react :as r]
            [website.test-support :as support]))

(r/defc <highlighted> [text committed!]
  (r/use-layout-effect (fn [] (committed! text) nil) [text committed!])
  [:pre [:code [:span {:style {:color "#fb4934"}} "defn"] " " text]])

(r/defc <formatter> [server? lazy-view text committed!]
  (let [[defer? set-defer!] (r/use-state #(and (not server?) (restore/initial-hydration?)))
        complete! (r/use-callback (fn [value] (set-defer! false) (committed! value)) [committed!])]
    (hydration/use-boundary
      defer?
      #(r/as-element
         [r/suspense {:fallback (r/as-element [:pre [:code "plain fallback"]])}
          (if server? [<highlighted> text (fn [_])]
              (r/create-element lazy-view #js {:text text, :committed complete!}))])
      [server? lazy-view text complete!])))

(defn delayed-view []
  (let [*release (atom nil)
        *ready? (atom false)
        promise (js/Promise. (fn [resolve _] (reset! *release resolve)))]
    {:ready? *ready?
     :release! (fn [] (@*release nil))
     :view (r/lazy
             (fn []
               (.then promise
                 (fn [_]
                   (reset! *ready? true)
                   #js {:default (r/reactify-component
                                   (fn [{:keys [text committed]}]
                                     [<highlighted> text committed]))}))))}))

(r/defc <boundary> [server? lazy-view committed! formatter-committed!]
  (let [[wrap? set-wrap!] (r/use-state false)]
    (r/use-layout-effect (fn [] (committed!) nil) [])
    [:div {:class (when wrap? "wrapped")
           :data-hydrating (:hydrate? @restore/*context)}
     [:button {:type "button", :on-click #(set-wrap! (not wrap?))} "Wrap"]
     [<formatter> server? lazy-view "greet" formatter-committed!]]))

(deftest suspense-preserves-server-code-before-and-after-deferred-hydration
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          *shell-committed? (atom false)
          *formatter-commits (atom [])
          *errors (atom [])
          before-restore @restore/*context
          before-flush (r/batching-flush)
          delayed (delayed-view)]
      (-> (go-promise
            (restore/begin! {:hydrate? true})
            (set! (.-innerHTML element)
                  (r/render-to-string [<boundary> true nil (fn []) (fn [_])]))
            (let [pre (.querySelector element "pre")
                  span (.querySelector element "span")]
              (reset! *root
                (r/hydrate-root element
                  [<boundary> false (:view delayed)
                   #(reset! *shell-committed? true) #(swap! *formatter-commits conj %)]
                  {:on-recoverable-error #(swap! *errors conj (str %))}))
              (let [original (r/batching-flush)]
                (await! (support/wait-for! #(deref *shell-committed?)))
                (is (false? @(:ready? delayed)))
                (is (empty? @*formatter-commits))
                (is (identical? pre (.querySelector element "pre")))
                (is (identical? span (.querySelector element "span")))
                (is (= "defn greet" (.-textContent pre)) "SSR stays visible, never the plain fallback")
                (.click (.querySelector element "button"))
                (await! (support/wait-for! #(= "wrapped" (.-className (.-firstElementChild element)))))
                (is (identical? span (.querySelector element "span")) "The outer control works before inner hydration")
                (restore/hydrated!)
                (await! (support/settle!))
                (is (identical? original (r/batching-flush)) "Pending hydration leaves unrelated batching unchanged")
                ((:release! delayed))
                (await! (support/wait-for! #(= ["greet"] @*formatter-commits)))
                (is (identical? pre (.querySelector element "pre")))
                (is (identical? span (.querySelector element "span")))
                (is (identical? original (r/batching-flush)))
                (is (empty? @*errors) (pr-str @*errors)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (reset! restore/*context before-restore)
                      (r/set-batching-flush! before-flush)
                      (done)))))))

(r/defc <deferred-section> [id server? lazy-view text *commits]
  (let [committed! (r/use-callback #(swap! *commits update id (fnil conj []) %) [id *commits])]
    [:section {:data-deferred (name id)}
     [<formatter> server? lazy-view text committed!]]))

(r/defc <overlapping> [server? views *text committed! *commits]
  (let [[clicked? set-clicked!] (r/use-state false)
        text @*text]
    (r/use-layout-effect (fn [] (committed!) nil) [])
    [:div
     [:button {:on-click #(set-clicked! true)} (if clicked? "Clicked" "Click")]
     (for [id [:first :second]]
       ^{:key id}
       [<deferred-section> id server? (get views id) text *commits])]))

(deftest overlapping-ssr-boundaries-complete-independently-and-receive-latest-props
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          before-restore @restore/*context
          before-flush (r/batching-flush)
          *shell-committed? (atom false)
          *commits (atom {})
          *errors (atom [])
          *text (r/atom "original")
          gates {:first (delayed-view), :second (delayed-view)}
          views (into {} (map (fn [[id gate]] [id (:view gate)])) gates)
          committed! #(reset! *shell-committed? true)]
      (-> (go-promise
            (restore/begin! {:hydrate? true})
            (set! (.-innerHTML element)
                  (r/render-to-string [<overlapping> true {} *text (fn []) *commits]))
            (let [first-span (.querySelector element "[data-deferred='first'] span")
                  second-span (.querySelector element "[data-deferred='second'] span")]
              (reset! *root
                (r/hydrate-root element [<overlapping> false views *text committed! *commits]
                  {:on-recoverable-error #(swap! *errors conj (str %))}))
              (let [original (r/batching-flush)]
                (await! (support/wait-for! #(deref *shell-committed?)))
                (.click (.querySelector element "button"))
                (await! (support/wait-for! #(= "Clicked" (.-textContent (.querySelector element "button")))))
                ((get-in gates [:first :release!]))
                (await! (support/wait-for! #(= ["original"] (:first @*commits))))
                (is (nil? (:second @*commits)) "The other SSR boundary remains unhydrated")
                (is (identical? second-span (.querySelector element "[data-deferred='second'] span")))
                (reset! *text "intermediate")
                (reset! *text "latest")
                (await! (support/wait-for! #(= "latest" (last (:first @*commits)))))
                (is (nil? (:second @*commits)))
                (is (= "defn original" (.-textContent (.querySelector element "[data-deferred='second'] pre"))))
                (is (identical? second-span (.querySelector element "[data-deferred='second'] span")))
                (is (identical? original (r/batching-flush)))
                ((get-in gates [:second :release!]))
                (await! (support/wait-for! #(= "latest" (last (:second @*commits)))))
                (is (identical? first-span (.querySelector element "[data-deferred='first'] span")))
                (is (identical? second-span (.querySelector element "[data-deferred='second'] span")))
                (is (= "defn latest" (.-textContent (.querySelector element "[data-deferred='second'] pre"))))
                (is (empty? @*errors) (pr-str @*errors)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (r/unmount @*root))
                      (reset! restore/*context before-restore)
                      (r/set-batching-flush! before-flush)
                      (done)))))))

(r/defc <tracked-value> [text *events]
  (r/use-layout-effect
    (fn []
      (swap! *events conj [:commit text])
      #(swap! *events conj [:cleanup text]))
    [text *events])
  [:span text])

(r/defc <cached-boundary> [text *events]
  (hydration/use-boundary
    true
    #(r/as-element [r/suspense {:fallback (r/as-element [:p "Waiting"])}
                    [<tracked-value> text *events]])
    [text *events]))

(r/defc <discarded> [gate] (throw gate))

(deftest cached-completion-strict-replay-and-discarded-renders-keep-scoped-lifetimes
  (async done
    (let [element (.createElement js/document "div")
          *root (atom nil)
          *events (atom [])
          before-restore @restore/*context
          delayed (delayed-view)
          *release (atom nil)
          gate (js/Promise. (fn [resolve _] (reset! *release resolve)))]
      (-> (go-promise
            (let [root (await! (support/create-root! element))
                  original (r/batching-flush)]
              (reset! *root root)
              (await! (support/render! root
                        [r/suspense {:fallback (r/as-element [:p "Waiting"])}
                         [:<> [<cached-boundary> "Abandoned" *events] [<discarded> gate]]]))
              (is (= "Waiting" (.-textContent element)))
              (is (empty? @*events) "A discarded later-sibling render commits no child effects")
              (await! (support/render! root [:> r/strict-mode [<cached-boundary> "Ready" *events]]))
              (await! (support/wait-for! #(= "Ready" (.-textContent element))))
              (is (>= (count (filter #{[:commit "Ready"]} @*events)) 2) "StrictMode replays the cached commit")
              (is (= 1 (- (count (filter #{[:commit "Ready"]} @*events))
                          (count (filter #{[:cleanup "Ready"]} @*events))))
                  "Only the current committed child remains mounted")
              (await! (support/render! root [:> r/strict-mode [<cached-boundary> "Latest" *events]]))
              (await! (support/wait-for! #(= "Latest" (.-textContent element))))
              (is (some #{[:cleanup "Ready"]} @*events))
              (is (some #{[:commit "Latest"]} @*events))
              (restore/begin! {:hydrate? true})
              (await! (support/render! root
                        [<formatter> false (:view delayed) "Pending"
                         #(swap! *events conj [:late-commit %])]))
              (await! (support/wait-for! #(= "plain fallback" (.-textContent element))))
              (await! (support/render! root [:p "Away"]))
              (let [events @*events]
                (@*release nil)
                ((:release! delayed))
                (await! (support/wait-for! #(deref (:ready? delayed))))
                (await! (support/settle!))
                (is (= "Away" (.-textContent element)) "An abandoned promise cannot remount its old subtree")
                (is (= events @*events)))
              (is (= (count (filter #(= :commit (first %)) @*events))
                     (count (filter #(= :cleanup (first %)) @*events)))
                  "All committed child effects are cleaned after replacement")
              (is (identical? original (r/batching-flush)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (reset! restore/*context before-restore)
                      (done)))))))
