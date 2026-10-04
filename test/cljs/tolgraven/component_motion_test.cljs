(ns tolgraven.component-motion-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [react :as react]
            [react-dom :as react-dom]
            [reagent.core :as r]
            [reagent.dom.client :as dom]
            [tolgraven.component :as component]
            [tolgraven.component.motion :as motion]
            [tolgraven.macros :refer-macros [defc]]))

(defn- flush! [] (react-dom/flushSync #(r/flush)))
(defn- render! [root form] (react-dom/flushSync #(.render root (r/as-element form))))
(defn- tick! ([] (tick! 45)) ([ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms)))))
(defn- fixture []
  (let [element (.createElement js/document "div") root (dom/create-root element)]
    (.appendChild (.-body js/document) element)
    {:element element :root root
     :close! #(do (react-dom/flushSync (fn [] (dom/unmount root))) (.remove element))}))

(defonce *mounts (atom 0))
(defonce *unmounts (atom 0))
(defc <item> {:features [[:appear "opacity"] [:exit {:timeout-ms 180}]]} [item]
  :let [_ (swap! *mounts inc)]
  (r/with-let [_ nil]
    [:button {:data-item (:id item) :style {:transition "opacity 90ms linear"}
              :class "item"} (:title item)]
    (finally (swap! *unmounts inc))))
(defc <list> {:features [:presence]} [items]
  (into [:section {:data-list true}]
        (map (fn [item] ^{:key (:id item)} [<item> item]) items)))
(defc <seen> {:features [:props :seen]} [spec]
  [:div {:class "original"} "Seen"])
(defc <domain-map> {:features [:props]} [{:keys [title]}]
  [:p title])
(defc <destructured-spec> {:features [:props]} [{:keys [props]}]
  [:p (:title props)])

(deftest spec-inference-does-not-consume-domain-maps
  (let [{:keys [root element close!]} (fixture)]
    (try
      (render! root [<domain-map> {:title "Domain" :props {:id "must-not-leak"} :depends [:invalid]}])
      (is (= "Domain" (.-textContent element)))
      (is (nil? (.querySelector element "#must-not-leak")))
      (is (empty? (component/dependencies (component/component-spec <domain-map>)
                                         [{:depends [:invalid]}])))
      (render! root [<destructured-spec> {:props {:id "inferred" :title "Spec"}}])
      (is (some? (.querySelector element "#inferred")))
      (finally (close!)))))

(deftest presence-retains-until-exit-and-cancels-on-reentry
  (async done
    (let [{:keys [root element close!]} (fixture)
          item {:id "one" :title "First"} *node (atom nil)
          style (.createElement js/document "style")]
      (set! (.-textContent style) ".item.appear-wrapper {opacity:0}.item.appear-wrapper.appeared {opacity:1}")
      (.appendChild (.-head js/document) style)
      (reset! *mounts 0) (reset! *unmounts 0)
      (render! root [<list> [item]])
      (reset! *node (.querySelector element "button"))
      (is (= "SECTION" (.-tagName (.-firstElementChild element))))
      (is (= 2 (.-length (.querySelectorAll element "*"))) "Only original parent and child DOM")
      (-> (tick! 160)
          (.then (fn [_]
                   (flush!)
                   (is (.contains (.-classList @*node) "appeared"))
                   (render! root [<list> []])
                   (is (identical? @*node (.querySelector element "button")))
                   (is (.contains (.-classList @*node) "exiting"))
                   (is (.hasAttribute @*node "inert"))
                   (is (= 0 @*unmounts) "A live component remains during exit")
                   ;; Re-enter before the exit completes; cancel its old callback.
                   (render! root [<list> [(assoc item :title "Returned")]])
                   (is (identical? @*node (.querySelector element "button")))
                   (is (not (.contains (.-classList @*node) "exiting")))
                   (tick! 220)))
          (.then (fn [_]
                   (flush!)
                   (is (= "Returned" (.-textContent element)))
                   (is (= 1 @*mounts) "Re-entry preserves the component instance")
                   (render! root [<list> []])
                   (is (some? (.querySelector element "button")))
                   (tick! 220)))
          (.then (fn [_]
                   (flush!)
                   (is (nil? (.querySelector element "button")) "Exited DOM is actually removed")
                   (tick!)))
          (.then (fn [_] (is (= 1 @*unmounts))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (.remove style) (done)))))))

(deftest seen-merges-refs-and-cleans-up-observers
  (async done
    (let [{:keys [root element close!]} (fixture)
          original js/IntersectionObserver *callback (atom nil) *disconnects (atom 0)
          *observed (atom nil) *ref (atom nil)
          fake (fn [callback options]
                 (reset! *callback callback)
                 (is (= 0.5 (.-threshold options)))
                 #js {:observe #(reset! *observed %)
                      :disconnect #(swap! *disconnects inc)})]
      (set! js/IntersectionObserver fake)
      (render! root [<seen> {:props {:ref #(reset! *ref %)} :seen {:class "slide-in"}}])
      (is (and (identical? @*observed @*ref) (identical? @*ref (.-firstElementChild element))))
      (is (= 1 (.-length (.querySelectorAll element "*"))))
      (is (.contains (.-classList @*ref) "original"))
      (@*callback #js [#js {:isIntersecting true :intersectionRatio 0.75}] #js {})
      (-> (tick!)
          (.then (fn [_]
                   (flush!)
                   (is (.contains (.-classList @*ref) "appeared"))
                   (@*callback #js [#js {:isIntersecting true :intersectionRatio 0.25}] #js {})
                   (tick!)))
          (.then (fn [_]
                   (flush!)
                   (is (not (.contains (.-classList @*ref) "appeared")))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!)
                      (is (nil? @*ref))
                      (is (pos? @*disconnects))
                      (set! js/IntersectionObserver original) (done)))))))

(deftest reduced-motion-and-unanimated-exits-remove-without-delay
  (async done
    (let [{:keys [root element close!]} (fixture) original motion/reduced-motion?]
      (set! motion/reduced-motion? (constantly true))
      (render! root [<list> [{:id "reduced" :title "Reduced"}]])
      (is (.contains (.-classList (.querySelector element "button")) "appeared"))
      (is (= "none" (.. (.querySelector element "button") -style -transition)))
      (render! root [<list> []])
      (-> (tick!)
          (.then (fn [_] (flush!) (is (nil? (.querySelector element "button")))) )
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (set! motion/reduced-motion? original) (done)))))))

(deftest strict-mode-visibility-restarts-and-once-is-sticky
  (async done
    (let [{:keys [root element close!]} (fixture)
          original js/IntersectionObserver *callbacks (atom []) *disconnects (atom 0)
          fake (fn [callback _]
                 (swap! *callbacks conj callback)
                 #js {:observe (fn [_]) :disconnect #(swap! *disconnects inc)})]
      (set! js/IntersectionObserver fake)
      (render! root [:> react/StrictMode [<seen> {:seen {:class "opacity" :once? true}}]])
      (is (>= (count @*callbacks) 2) "StrictMode restarts the observer after effect cleanup")
      ((last @*callbacks) #js [#js {:isIntersecting true :intersectionRatio 0.8}]
       #js {:disconnect #(swap! *disconnects inc)})
      (-> (tick!)
          (.then (fn [_]
                   (flush!)
                   (is (.contains (.-classList (.-firstElementChild element)) "appeared"))
                   (is (pos? @*disconnects))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (set! js/IntersectionObserver original) (done)))))))

(deftest exit-deadline-releases-paused-animations-and-parent-unmount-cleans-up
  (async done
    (let [{:keys [root element close!]} (fixture)
          *finish (atom nil) never (js/Promise. (fn [resolve _] (reset! *finish resolve)))]
      (render! root [<list> [{:id "paused" :title "Paused"}]])
      (set! (.-getAnimations (.querySelector element "button")) (fn [] #js [#js {:finished never}]))
      (render! root [<list> []])
      (is (some? (.querySelector element "button")))
      (-> (tick! 240)
          (.then (fn [_]
                   (flush!)
                   (is (nil? (.querySelector element "button")) "Deadline releases a paused/infinite animation")
                   (render! root [<list> [{:id "parent" :title "Parent cleanup"}]])
                   (set! (.-getAnimations (.querySelector element "button")) (fn [] #js [#js {:finished never}]))
                   (render! root [<list> []])
                   (render! root [:aside "Parent removed"])
                   (@*finish nil)
                   (tick!)))
          (.then (fn [_] (flush!) (is (= "Parent removed" (.-textContent element)))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))

(defc <failed-item> {:features [:error-boundary [:exit {:timeout-ms 40}]]} []
  (throw (js/Error. "Failed before creating motion root")))
(defc <failure-list> {:features [:presence]} [show?]
  [:section (when show? ^{:key :failed} [<failed-item>])])

(deftest parent-deadline-removes-error-fallbacks-too
  (async done
    (let [{:keys [root element close!]} (fixture)]
      (render! root [<failure-list> true])
      (is (some? (.querySelector element "[role=alert]")))
      (render! root [<failure-list> false])
      (is (some? (.querySelector element "[role=alert]")))
      (-> (tick! 80)
          (.then (fn [_] (flush!) (is (nil? (.querySelector element "[role=alert]")))))
          (.catch #(is false (str %)))
          (.finally (fn [] (close!) (done)))))))
