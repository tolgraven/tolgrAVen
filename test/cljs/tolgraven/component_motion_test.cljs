(ns tolgraven.component-motion-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [tolgraven.test-support :as support]
            [cljs.test :refer-macros [deftest is async]]
            [tolgraven.react :as react]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [tolgraven.component :as component]
            [tolgraven.component.motion :as motion]
            [tolgraven.component.restore :as restore]
            [tolgraven.macros :refer-macros [defc]]))
(defn- flush! [] (support/settle!))
(defn- render! [root form] (support/render! root form))
(defn- tick! ([] (tick! 45)) ([ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms)))))
(defn- fixture
  []
  (go-promise (let [element (.createElement js/document "div")
                    root (await! (support/create-root! element))]
                (.appendChild (.-body js/document) element)
                {:element element,
                 :root root,
                 :close! #(do (do (support/unmount! root)) (.remove element))})))
(defonce *mounts (atom 0))
(defonce *unmounts (atom 0))
(defc <item>
  {:features [[:appear "opacity"] [:exit {:timeout-ms 180}]]}
  [item]
  :let
  [_ (swap! *mounts inc)]
  (r/with-let [_ nil]
              [:button
               {:data-item (:id item), :style {:transition "opacity 90ms linear"}, :class "item"}
               (:title item)]
              (finally (swap! *unmounts inc))))
(defc <list>
  {:features [:presence]}
  [items]
  (into [:section {:data-list true}] (map (fn [item] ^{:key (:id item)} [<item> item]) items)))
(defc <seen> {:features [:props :seen]} [spec] [:div {:class "original"} "Seen"])
(defc <domain-map> {:features [:props]} [{:keys [title]}] [:p title])
(defc <destructured-spec> {:features [:props]} [{:keys [props]}] [:p (:title props)])
(deftest high-frame-rate-hint-is-optional-and-preserves-animation-timing
  (let [supported #js {:frameRate "auto", :playbackRate 1, :currentTime 50}
        unsupported #js {:playbackRate 1}
        rejected #js {}]
    (js/Object.defineProperty rejected "frameRate"
      #js {:get (fn [] "auto")
           :set (fn [_] (throw (js/Error. "Frame-rate hint rejected")))})
    (with-redefs [motion/reduced-motion? (constantly false)]
      (motion/prefer-high-frame-rate! [unsupported rejected supported]))
    (is (= "highest" (.-frameRate supported))
        "A rejected hint does not stop the remaining page layer")
    (is (= 1 (.-playbackRate supported)))
    (is (= 50 (.-currentTime supported)))
    (is (not (.hasOwnProperty unsupported "frameRate"))
        "Unsupported browsers receive no inert expando property")
    (set! (.-frameRate supported) "auto")
    (with-redefs [motion/reduced-motion? (constantly true)]
      (motion/prefer-high-frame-rate! [supported]))
    (is (= "auto" (.-frameRate supported)) "Reduced motion receives no request")))

(deftest spec-inference-does-not-consume-domain-maps
  (async
    done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))]
            (try (await! (render!
                           root
                           [<domain-map>
                            {:title "Domain", :props {:id "must-not-leak"}, :depends [:invalid]}]))
                 (is (= "Domain" (.-textContent element)))
                 (is (nil? (.querySelector element "#must-not-leak")))
                 (is (empty? (component/dependencies (component/component-spec <domain-map>)
                                                     [{:depends [:invalid]}])))
                 (await! (render! root
                                  [<destructured-spec> {:props {:id "inferred", :title "Spec"}}]))
                 (is (some? (.querySelector element "#inferred")))
                 (finally (close!)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(deftest presence-retains-until-exit-and-cancels-on-reentry
  (async
    done
    (->
      (go-promise
        (let [{:keys [root element close!]} (await! (fixture))
              item {:id "one", :title "First"}
              *node (atom nil)
              style (.createElement js/document "style")]
          (set! (.-textContent style)
                ".item.appear-wrapper {opacity:0}.item.appear-wrapper.appeared {opacity:1}")
          (.appendChild (.-head js/document) style)
          (reset! *mounts 0)
          (reset! *unmounts 0)
          (await! (render! root [<list> [item]]))
          (reset! *node (.querySelector element "button"))
          (is (= "SECTION" (.-tagName (.-firstElementChild element))))
          (is (= 2 (.-length (.querySelectorAll element "*"))) "Only original parent and child DOM")
          (-> (tick! 160)
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (.contains (.-classList @*node) "appeared"))
                                   (await! (render! root [<list> []]))
                                   (is (identical? @*node (.querySelector element "button")))
                                   (is (.contains (.-classList @*node) "exiting"))
                                   (is (.hasAttribute @*node "inert"))
                                   (is (= 0 @*unmounts) "A live component remains during exit")
                                   ;; Re-enter before the exit completes; cancel its old
                                   ;; callback.
                                   (await! (render! root [<list> [(assoc item :title "Returned")]]))
                                   (is (identical? @*node (.querySelector element "button")))
                                   (is (not (.contains (.-classList @*node) "exiting")))
                                   (tick! 220))))
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (= "Returned" (.-textContent element)))
                                   (is (= 1 @*mounts) "Re-entry preserves the component instance")
                                   (await! (render! root [<list> []]))
                                   (is (some? (.querySelector element "button")))
                                   (tick! 220))))
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (nil? (.querySelector element "button"))
                                       "Exited DOM is actually removed")
                                   (tick!))))
              (.then (fn [_] (is (= 1 @*unmounts))))
              (.catch #(is false (str %)))
              (.finally (fn [] (close!) (.remove style) (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest seen-merges-refs-and-cleans-up-observers
  (async
    done
    (->
      (go-promise
        (let [{:keys [root element close!]} (await! (fixture))
              original js/IntersectionObserver
              *callback (atom nil)
              *disconnects (atom 0)
              *observed (atom nil)
              *ref (atom nil)
              fake (fn [callback options]
                     (reset! *callback callback)
                     (is (= 0.5 (.-threshold options)))
                     #js {:observe #(reset! *observed %), :disconnect #(swap! *disconnects inc)})]
          (set! js/IntersectionObserver fake)
          (await! (render! root
                           [<seen> {:props {:ref #(reset! *ref %)}, :seen {:class "slide-in"}}]))
          (is (and (identical? @*observed @*ref) (identical? @*ref (.-firstElementChild element))))
          (is (= 1 (.-length (.querySelectorAll element "*"))))
          (is (.contains (.-classList @*ref) "original"))
          (@*callback #js [#js {:isIntersecting true, :intersectionRatio 0.75}] #js {})
          (-> (tick!)
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (.contains (.-classList @*ref) "appeared"))
                                   (@*callback
                                    #js [#js {:isIntersecting true, :intersectionRatio 0.25}]
                                    #js {})
                                   (tick!))))
              (.then (fn [_]
                       (go-promise (await! (flush!))
                                   (is (not (.contains (.-classList @*ref) "appeared"))))))
              (.catch #(is false (str %)))
              (.finally (fn []
                          (close!)
                          (is (nil? @*ref))
                          (is (pos? @*disconnects))
                          (set! js/IntersectionObserver original)
                          (done))))))
      (.catch (fn [error] (is false (str error)) (done))))))
(deftest reduced-motion-and-unanimated-exits-remove-without-delay
  (async done
         (-> (go-promise
               (let [{:keys [root element close!]} (await! (fixture))
                     original motion/reduced-motion?]
                 (set! motion/reduced-motion? (constantly true))
                 (await! (render! root [<list> [{:id "reduced", :title "Reduced"}]]))
                 (is (.contains (.-classList (.querySelector element "button")) "appeared"))
                 (is (= "none" (.. (.querySelector element "button") -style -transition)))
                 (await! (render! root [<list> []]))
                 (-> (tick!)
                     (.then (fn [_]
                              (go-promise (await! (flush!))
                                          (is (nil? (.querySelector element "button"))))))
                     (.catch #(is false (str %)))
                     (.finally (fn [] (close!) (set! motion/reduced-motion? original) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest strict-mode-visibility-restarts-and-once-is-sticky
  (async done
         (-> (go-promise
               (let [{:keys [root element close!]} (await! (fixture))
                     original js/IntersectionObserver
                     *callbacks (atom [])
                     *disconnects (atom 0)
                     fake (fn [callback _]
                            (swap! *callbacks conj callback)
                            #js {:observe (fn [_]), :disconnect #(swap! *disconnects inc)})]
                 (set! js/IntersectionObserver fake)
                 (await! (render! root
                                  [:> react/strict-mode
                                   [<seen> {:seen {:class "opacity", :once? true}}]]))
                 (is (>= (count @*callbacks) 2)
                     "StrictMode restarts the observer after effect cleanup")
                 ((last @*callbacks)
                   #js [#js {:isIntersecting true, :intersectionRatio 0.8}]
                   #js {:disconnect #(swap! *disconnects inc)})
                 (-> (tick!)
                     (.then (fn [_]
                              (go-promise (await! (flush!))
                                          (is (.contains (.-classList (.-firstElementChild element))
                                                         "appeared"))
                                          (is (pos? @*disconnects)))))
                     (.catch #(is false (str %)))
                     (.finally (fn [] (close!) (set! js/IntersectionObserver original) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest exit-deadline-releases-paused-animations-and-parent-unmount-cleans-up
  (async done
         (-> (go-promise
               (let [{:keys [root element close!]} (await! (fixture))
                     *finish (atom nil)
                     never (js/Promise. (fn [resolve _] (reset! *finish resolve)))]
                 (await! (render! root [<list> [{:id "paused", :title "Paused"}]]))
                 (set! (.-getAnimations (.querySelector element "button"))
                       (fn [] #js [#js {:finished never}]))
                 (await! (render! root [<list> []]))
                 (is (some? (.querySelector element "button")))
                 (-> (tick! 240)
                     (.then (fn [_]
                              (go-promise
                                (await! (flush!))
                                (is (nil? (.querySelector element "button"))
                                    "Deadline releases a paused/infinite animation")
                                (await!
                                  (render! root [<list> [{:id "parent", :title "Parent cleanup"}]]))
                                (set! (.-getAnimations (.querySelector element "button"))
                                      (fn [] #js [#js {:finished never}]))
                                (await! (render! root [<list> []]))
                                (await! (render! root [:aside "Parent removed"]))
                                (@*finish nil)
                                (tick!))))
                     (.then (fn [_]
                              (go-promise (await! (flush!))
                                          (is (= "Parent removed" (.-textContent element))))))
                     (.catch #(is false (str %)))
                     (.finally (fn [] (close!) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(defc <failed-item>
  {:features [:error-boundary [:exit {:timeout-ms 40}]]}
  []
  (throw (js/Error. "Failed before creating motion root")))
(defc <failure-list>
  {:features [:presence]}
  [show?]
  [:section (when show? (with-meta [<failed-item>] {:key :failed}))])
(deftest parent-deadline-removes-error-fallbacks-too
  (async done
         (-> (go-promise (let [{:keys [root element close!]} (await! (fixture))]
                           (await! (render! root [<failure-list> true]))
                           (is (some? (.querySelector element "[role=alert]")))
                           (await! (render! root [<failure-list> false]))
                           (is (some? (.querySelector element "[role=alert]")))
                           (-> (tick! 80)
                               (.then (fn [_]
                                        (go-promise (await! (flush!))
                                                    (is (nil? (.querySelector element
                                                                              "[role=alert]"))))))
                               (.catch #(is false (str %)))
                               (.finally (fn [] (close!) (done))))))
             (.catch (fn [error] (is false (str error)) (done))))))
(deftest restored-motion-roots-stay-visible-through-hydration-and-spa-mounts-enter
  (async
    done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context]
            (try (restore/begin! {:hydrate? true})
                 (await! (render! root (with-meta [<item> {:id 1, :title "SSR"}] {:key :initial})))
                 (let [node (.querySelector element "button")]
                   (is (nil? (.getAttribute node "data-stream-enter")))
                   (is (.contains (.-classList node) "appeared"))
                   (restore/hydrated!)
                   (await!
                     (render! root (with-meta [<item> {:id 1, :title "Hydrated"}] {:key :initial})))
                   (is (identical? node (.querySelector element "button")))
                   (is (nil? (.getAttribute node "data-stream-enter")))
                   (is (.contains (.-classList node) "appeared")))
                 (restore/navigate! "/another-page")
                 (await! (render! root (with-meta [<item> {:id 2, :title "SPA"}] {:key :spa})))
                 (let [node (.querySelector element "button")]
                   (is (nil? (.getAttribute node "data-stream-enter")))
                   (is (not (.contains (.-classList node) "appeared"))))
                 (restore/begin! {:back? true})
                 (await! (render! root (with-meta [<item> {:id 3, :title "Back"}] {:key :back})))
                 (let [node (.querySelector element "button")]
                   (is (nil? (.getAttribute node "data-stream-enter")))
                   (is (.contains (.-classList node) "appeared")))
                 (finally (close!) (reset! restore/*context context)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(defc <remembered-item>
  {:features [[:appear {:class "opacity" :remember-key :motion-test/remembered}]]}
  []
  [:div {:data-remembered true} "Previously visible"])

(deftest remembered-content-is-visible-on-its-next-mount
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context]
            (try
              (restore/navigate! "/motion-test")
              (react/dispatch [:component-motion/seen :motion-test/remembered])
              (await! (flush!))
              (await! (render! root [<remembered-item>]))
              (is (.contains (.-classList (.querySelector element "[data-remembered]")) "appeared"))
              (finally (close!) (reset! restore/*context context)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(defc <visible-owner>
  {:features [[:on-seen (fn [id] {:event [:test/visible-owner id]})]]}
  [id]
  [:section {:data-visible-owner id} "Owner"])

(react/reg-event-db :test/visible-owner
  (fn [db [_ id]] (update-in db [:test :visible-owner id] (fnil inc 0))))

(deftest visibility-effects-are-owned-by-the-existing-root
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                original js/IntersectionObserver
                *callback (atom nil) *observed (atom nil) *disconnects (atom 0)
                id (str (random-uuid))
                observer #js {:observe #(reset! *observed %) :disconnect #(swap! *disconnects inc)}]
            (try
              (set! js/IntersectionObserver
                    (fn [callback _] (reset! *callback callback) observer))
              (await! (render! root [<visible-owner> id]))
              (is (identical? @*observed (.querySelector element "section")))
              (is (= 1 (.-childElementCount element)))
              (@*callback #js [#js {:isIntersecting true :intersectionRatio 0.8}] observer)
              (@*callback #js [#js {:isIntersecting true :intersectionRatio 0.8}] observer)
              (is (= 1 (await! (support/state-at! [:test :visible-owner id]))))
              (await! (render! root nil))
              (is (>= @*disconnects 2) "Once visibility and unmount both release observation")
              (finally (close!) (set! js/IntersectionObserver original)))))
        (.catch #(is false (str %)))
        (.finally done))))
