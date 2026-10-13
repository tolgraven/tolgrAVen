(ns website.component-instance-features-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]


            [website.component :as component]
            [website.component.data :as data]
            [website.component.motion :as motion]
            [website.component.visibility :as visibility]
            [website.content.client :as content]
            [website.test-support :as support]
            [website.macros :refer-macros [defc defpage]]))

(defonce *config-calls (atom 0))
(defc <motion-counter>
  {:features [[:seen (fn [_]
                      (swap! *config-calls inc)
                      {:class "opacity", :remember-key :instance-test})]]}
  [spec]
  :let [*count (r/atom 0)]
  [:section [:button {:on-click #(swap! *count inc)} (str @*count)] [:input]])

(defn- with-root! [test!]
  (go-promise
    (let [element (.createElement js/document "div")
          root (await! (support/create-root! element))]
      (.appendChild (.-body js/document) element)
      (try (await! (test! root element))
           (finally (support/unmount! root) (.remove element))))))

(deftest instance-motion-off-on-off-preserves-body-and-releases-all-resources
  (async done
    (let [observer-before js/IntersectionObserver
          media-before (.-matchMedia js/window)
          subscribe-before r/observe-subscription!
          *observers (atom 0)
          *disconnects (atom 0)
          *queries (atom 0)
          *listeners (atom 0)
          *removals (atom 0)
          *subscriptions (atom 0)]
      (reset! *config-calls 0)
      (set! js/IntersectionObserver
            (fn [_ _]
              (swap! *observers inc)
              #js {:observe (fn [_]), :disconnect #(swap! *disconnects inc)}))
      (set! (.-matchMedia js/window)
            (fn [_]
              (swap! *queries inc)
              #js {:matches false
                   :addEventListener (fn [& _] (swap! *listeners inc))
                   :removeEventListener (fn [& _] (swap! *removals inc))}))
      (set! r/observe-subscription!
            (fn [query handle]
              (when (or (= :font/instance (first query))
                        (= [:state [:motion-seen :instance-test]] query))
                (swap! *subscriptions inc))
              (subscribe-before query handle)))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (let [disabled [[:seen false] [:appear nil] [:on-seen false] [:font false]]]
                  (await! (support/render! root [<motion-counter> disabled]))
                  (is (= [0 0 0 0] [@*config-calls @*observers @*queries @*subscriptions]))
                  (let [native (.querySelector element "section")
                        input (.querySelector element "input")]
                    (set! (.-value input) "Kept across feature changes")
                    (.click (.querySelector element "button"))
                    (await! (support/settle!))
                    (await! (support/render! root [<motion-counter> [[:seen {:threshold 0.25}]]]))
                    (is (identical? native (.querySelector element "section")))
                    (is (= "1" (.-textContent (.querySelector element "button"))))
                    (is (= "Kept across feature changes" (.-value (.querySelector element "input"))))
                    (is (= 1 @*observers))
                    (is (= 1 @*listeners))
                    (is (pos? @*config-calls))
                    (is (pos? @*subscriptions) "Active remembered appearance acquires its subscription")
                    (await! (support/render! root [<motion-counter> disabled]))
                    (is (= 1 @*disconnects))
                    (is (= 1 @*removals))
                    (let [counts [@*config-calls @*observers @*queries @*subscriptions]]
                      (await! (support/render! root [<motion-counter> disabled]))
                      (is (= counts [@*config-calls @*observers @*queries @*subscriptions])))
                    (await! (support/render! root [<motion-counter> [:seen]]))
                    (is (= 2 @*observers))
                    (is (identical? native (.querySelector element "section"))))))))
          (.then (fn [_]
                   (is (= 2 @*disconnects) "Unmount releases the newly added current owner")
                   (is (= 2 @*removals))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! js/IntersectionObserver observer-before)
                      (set! (.-matchMedia js/window) media-before)
                      (set! r/observe-subscription! subscribe-before)
                      (done)))))))

(deftest dynamic-owners-clean-up-in-reverse-current-declaration-order
  (async done
    (let [*events (atom [])
          ids (mapv #(keyword (str "instance-owner-" %)) (range 10))
          view (component/create-component "fixture" "dynamic-owners" {:spec true}
                                           (fn [_] (fn [_] [:section "Owned body"])))]
      (doseq [id ids]
        (component/register-feature!
          id {:root? false
              :setup (fn [_] id)
              :mount (fn [state _] (swap! *events conj [:mount state]))
              :unmount (fn [state] (swap! *events conj [:unmount state]))}))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [view (subvec ids 0 2)]))
                (let [native (.-firstElementChild element)]
                  (await! (support/render! root [view ids]))
                  (is (identical? native (.-firstElementChild element)))
                  (is (= (mapv #(vector :mount %) ids) @*events))
                  (await! (support/render! root [view (mapv #(vector % false) ids)]))
                  (is (= (mapv #(vector :unmount %) (reverse ids)) (subvec @*events 10)))
                  (await! (support/render! root [view [(last ids)]]))))))
          (.then (fn [_] (is (= [:unmount (last ids)] (last @*events)))))
          (.catch #(is false (str %)))
          (.finally done)))))

(defc <callback-counter> {:features [:lifecycle]} [spec] [:button "Callbacks"])
(deftest lifecycle-enablement-calls-current-init-and-exit-once
  (async done
    (let [*events (atom [])
          spec (fn [enabled? label]
                 {:features [[:lifecycle enabled?]]
                  :init (fn [_] (swap! *events conj [:init label]))
                  :exit (fn [_] (swap! *events conj [:exit label]))})]
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [<callback-counter> (spec false :disabled)]))
                (is (empty? @*events))
                (let [button (.querySelector element "button")]
                  (await! (support/render! root [<callback-counter> (spec true :first)]))
                  (await! (support/render! root [<callback-counter> (spec true :current)]))
                  (is (= [[:init :first]] @*events))
                  (await! (support/render! root [<callback-counter> (spec false :off)]))
                  (is (= [[:init :first] [:exit :current]] @*events))
                  (await! (support/render! root [<callback-counter> (spec true :last)]))
                  (is (identical? button (.querySelector element "button")))))))
          (.then (fn [_] (is (= [[:init :first] [:exit :current] [:init :last] [:exit :last]] @*events))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest shared-prefetch-replaces-only-changed-resources-and-cleans-up
  (async done
    (let [before js/IntersectionObserver
          effects-before (into {} (map (fn [id] [id (r/get-handler :fx id)])
                                      [:component-data/load :content/load]))
          *observers (atom [])
          *disconnects (atom 0)
          *events (atom [])]
      (set! js/IntersectionObserver
            (fn [callback options]
              (swap! *observers conj [callback options])
              #js {:observe (fn [_]), :disconnect #(swap! *disconnects inc)}))
      (doseq [id [:component-data/load :content/load]]
        (r/reg-fx id (fn [resources] (swap! *events conj [id resources]))))
      (-> (go-promise
            (doseq [[view event] [[component/<prefetch> :component-data/load]
                                 [content/<prefetch> :content/load]]]
              (reset! *observers [])
              (reset! *disconnects 0)
              (reset! *events [])
              (await!
                (with-root!
                  (fn [root _]
                    (go-promise
                      (await! (support/render! root [view [:first]]))
                      (await! (support/render! root [view (vec [:first])]))
                      (is (= 1 (count @*observers)) "Equal resource values keep the current observer")
                      (await! (support/render! root [view [:second]]))
                      (is (= 2 (count @*observers)))
                      (is (= 1 @*disconnects))
                      (let [[callback options] (last @*observers)]
                        (is (= 0 (.-threshold options)))
                        (is (= "800px 0px" (.-rootMargin options)))
                        (callback #js [#js {:isIntersecting true, :intersectionRatio 1}]
                                  #js {:disconnect (fn [])})
                        (await! (support/settle!))
                        (is (= [[event [:second]]] @*events)))))))
              (is (= 2 @*disconnects))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! js/IntersectionObserver before)
                      (doseq [[id handler] effects-before] (r/reg-fx id handler))
                      (done)))))))

(defc <optional-text>
  {:features [[:font {:family "Instance Font Ready"
                     :initial-family "Instance Font"
                     :fallback "monospace"
                     :property "--instance-font"}]]}
  [spec *visible?]
  (when @*visible? [:p "Optional text"]))

(deftest font-gating-releases-a-conditional-root-without-invalid-hiccup
  (async done
    (let [before r/observe-subscription!
          *queries (atom 0)
          *visible? (r/atom false)]
      (set! r/observe-subscription!
            (fn [query handle]
              (when (= :font/instance (first query)) (swap! *queries inc))
              (before query handle)))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [<optional-text> {} *visible?]))
                (is (nil? (.-firstElementChild element)))
                (is (zero? @*queries))
                (reset! *visible? true)
                (await! (support/settle!))
                (is (pos? @*queries))
                (let [native (.-firstElementChild element)]
                  (await! (support/render! root [<optional-text> [[:font false]] *visible?]))
                  (is (identical? native (.-firstElementChild element)))
                  (let [count @*queries]
                    (await! (support/render! root [<optional-text> [[:font nil]] *visible?]))
                    (is (= count @*queries)))
                  (reset! *visible? false)
                  (await! (support/settle!))
                  (is (nil? (.-firstElementChild element)))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! r/observe-subscription! before) (done)))))))

(defc <instance-exit>
  {:features [[:exit (fn [spec *enabled?]
                      (when (and (map? spec) @*enabled?) {:timeout-ms 3000}))]]}
  [spec *enabled?]
  [:button "Retained"])
(defc <fragment-presence> {:features [:presence]} [spec *present? *exit?]
  [:<> (when @*present? ^{:key :retained} [<instance-exit> [] *exit?])])

(deftest fragment-presence-uses-current-child-exit-and-acknowledges-disable
  (async done
    (let [before motion/finish-animation!
          *starts (atom 0)
          *stops (atom 0)
          *present? (r/atom true)
          *exit? (r/atom true)]
      (set! motion/finish-animation!
            (fn [_ _ _] (swap! *starts inc) #(swap! *stops inc)))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [<fragment-presence> {} *present? *exit?]))
                (let [button (.querySelector element "button")]
                  (reset! *present? false)
                  (await! (support/settle!))
                  (is (identical? button (.querySelector element "button")))
                  (is (= 1 @*starts))
                  (reset! *exit? false)
                  (await! (support/settle!))
                  (await! (support/wait-for! #(nil? (.querySelector element "button"))))
                  (is (= 1 @*stops) "Disabling the same exiting token cancels its old completion work")
                  (reset! *present? true)
                  (await! (support/settle!))
                  (is (some? (.querySelector element "button")))
                  (reset! *present? false)
                  (await! (support/settle!))
                  (is (nil? (.querySelector element "button")) "An ineligible child is removed immediately")
                  (is (= 1 @*starts))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! motion/finish-animation! before) (done)))))))

(deftest throwing-cleanup-still-releases-all-current-owners
  (async done
    (let [*released (atom [])
          ids [:cleanup-first :cleanup-throws :cleanup-last]
          owned (component/create-component "fixture" "throwing-cleanup"
                                             {:features ids}
                                             (fn [] (fn [] [:section "Owned"])))
          boundary (component/create-component "fixture" "cleanup-boundary"
                                                {:features [:error-boundary]}
                                                (fn [shown?] (fn [shown?] [:div (when shown? [owned])]))) ]
      (doseq [id ids]
        (component/register-feature!
          id {:root? false
              :setup (fn [_] id)
              :unmount (fn [state]
                         (swap! *released conj state)
                         (when (= :cleanup-throws state) (throw (js/Error. "Expected cleanup failure"))))}))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [boundary true]))
                (await! (support/render! root [boundary false]))
                (is (= (vec (reverse ids)) @*released))
                (is (some? (.querySelector element "[role=alert]"))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest disabled-custom-functions-and-legacy-layout-props-preserve-contracts
  (async done
    (let [*calls (atom [])
          defaults [[:instance-transform (fn [_] (swap! *calls conj :config) :default)]
                    [:instance-wrapper (fn [_] (swap! *calls conj :wrapper-config) :default)]
                    [:container {:tag :section, :props {:class "default-layout", :style {:color "red"}}}]]
          view (component/create-component "fixture" "disabled-custom"
                                           {:spec true, :features defaults}
                                           (fn [_] (fn [_] [:p "Body"])))]
      (component/register-feature!
        :instance-transform {:transform (fn [form _ _] (swap! *calls conj :transform) form)})
      (component/register-feature!
        :instance-wrapper {:wrap (fn [form _ _] (swap! *calls conj :wrap) form)})
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root
                          [view {:container {:tag :article, :props {:class "caller-layout", :style {:background-color "blue"}}}
                                 :features [[:instance-transform false] [:instance-wrapper nil]]}]))
                (is (empty? @*calls))
                (let [surface (.querySelector element "article.default-layout.caller-layout")]
                  (is (some? surface))
                  (is (= "red" (.. surface -style -color)))
                  (is (= "blue" (.. surface -style -backgroundColor)))))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest lean-sentinel-restarts-after-strict-mode-cleanup-and-supports-fallback
  (async done
    (let [before js/IntersectionObserver
          *callbacks (atom [])
          *disconnects (atom 0)
          *fired (atom 0)
          callback #(swap! *fired inc)]
      (set! js/IntersectionObserver
            (fn [callback _]
              (swap! *callbacks conj callback)
              #js {:observe (fn [_]), :disconnect #(swap! *disconnects inc)}))
      (-> (with-root!
            (fn [root _]
              (go-promise
                (await! (support/render! root [:> r/strict-mode
                                              [visibility/<sentinel> {:callback callback}]]))
                (is (= 2 (count @*callbacks)))
                (is (= 1 @*disconnects))
                ((last @*callbacks) #js [#js {:isIntersecting true, :intersectionRatio 1}]
                                    #js {:disconnect (fn [])})
                (is (= 1 @*fired))
                (await! (support/render! root [:p "Removed"]))
                (is (= 2 @*disconnects))
                (set! js/IntersectionObserver js/undefined)
                (await! (support/render! root [visibility/<sentinel> {:callback callback}]))
                (is (= 2 @*fired) "Without IntersectionObserver, the shared adapter dispatches immediately"))))
          (.catch #(is false (str %)))
          (.finally (fn [] (set! js/IntersectionObserver before) (done)))))))

(defpage <required-boundary> {:container false} [spec]
  (throw (js/Error. "Required page recovery")))
(defc <lean-domain> {:spec false} [data] [:p (pr-str (:features data))])
(deftest required-page-and-declaration-data-cannot-be-disabled
  (async done
    (let [before data/state
          *reads (atom [])
          resource {:source :instance-required-data}
          view (component/create-component "fixture" "required-data"
                                           {:spec true, :depends [resource]}
                                           (fn [_] (fn [_] [:p "Dependency-owned body"])))]
      (data/register-source! :instance-required-data {:read (fn [_] {:ready? true, :value :cached})})
      (set! data/state (fn [resources] (swap! *reads conj resources) (before resources)))
      (-> (with-root!
            (fn [root element]
              (go-promise
                (await! (support/render! root [<required-boundary> [[:error-boundary false]]]))
                (is (some? (.querySelector element "[role=alert]")))
                (is (.includes (.-textContent element) "Required page recovery"))
                (await! (support/render! root [view [[:data false]]]))
                (is (= "Dependency-owned body" (.-textContent element)))
                (is (some #{[resource]} @*reads) "The declaration-owned data boundary remains mounted")
                (await! (support/render! root [<lean-domain> {:features [[:unknown-feature true]]}]))
                (is (= "[[:unknown-feature true]]" (.-textContent element)))
                (is (false? (get-in (component/component-spec <lean-domain>) [:options :spec]))))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (set! data/state before)
                      (swap! data/*sources dissoc :instance-required-data)
                      (done)))))))
