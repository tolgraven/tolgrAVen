(ns website.component-motion-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [clojure.string :as string]
            [website.test-support :as support]
            [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]
            [website.render-context :as context]
            [website.events]
            [website.subs]
            [website.component :as component]
            [website.component.motion :as motion]
            [website.component.restore :as restore]
            [website.macros :refer-macros [defc]]))
(defn- flush! [] (support/settle!))
(defn- render! [root form] (support/render! root form))
(defn- tick! ([] (tick! 45)) ([ms] (js/Promise. (fn [resolve _] (js/setTimeout resolve ms)))))
(defn- fixture
  []
  (go-promise (let [element (.createElement js/document "div")
                    root (await! (support/create-root! element))]
                (set! (.-cssText (.-style element)) "position:fixed;top:0;left:0;width:50vw")
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
(defonce *presence-renders (atom 0))
(defc <fresh-callback-presence>
  {:features [:presence]}
  [label]
  (swap! *presence-renders inc)
  [:section [:button {:on-click (fn [_] nil)} label]])

(deftest presence-commits-with-fresh-callbacks-do-not-trigger-idle-renders
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))]
            (reset! *presence-renders 0)
            (try
              (await! (render! root [<fresh-callback-presence> "Initial"]))
              (await! (tick! 120))
              (let [committed @*presence-renders]
                (await! (tick! 180))
                (is (= committed @*presence-renders) "Commit bookkeeping does not schedule another frame")
                (is (= "Initial" (.-textContent element))))
              (await! (render! root [<fresh-callback-presence> "Updated"]))
              (is (= "Updated" (.-textContent element)))
              (let [committed @*presence-renders]
                (await! (tick! 180))
                (is (= committed @*presence-renders) "Real parent updates also settle"))
              (finally (close!)))))
        (.catch #(is false (str %)))
        (.finally done))))
(defc <seen> {:features [:props :seen]} [spec] [:div {:class "original"} "Seen"])
(defc <appear> {:features [:props [:appear "opacity"]]} [spec] [:div "Appear"])

(deftest parent-owned-clipped-entrance-does-not-wait-for-its-own-transform
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                style (.createElement js/document "style")]
            (try
              ;; The initial child is entirely above its clipping parent, just
              ;; like a reply's slide-behind transform. Native IO cannot see it.
              (.add (.-classList element) "motion-clipped-fixture")
              (set! (.-textContent style)
                    ".motion-clipped-fixture .appear-wrapper {height:4rem;transform:translateY(-120%)} .motion-clipped-fixture .appear-wrapper.appeared {transform:none}")
              (.appendChild (.-head js/document) style)
              (await! (render! root
                       [:div {:style {:overflow "hidden", :height "5rem"}}
                        [<appear> {:appear {:class "slide-behind", :viewport? false}}]]))
              (await! (tick! 150))
              (let [child (.querySelector element ".appear-wrapper")
                    bounds (.getBoundingClientRect child)
                    parent (.getBoundingClientRect (.-parentElement child))]
                (is (.contains (.-classList child) "appeared"))
                (is (>= (.-top bounds) (.-top parent)))
                (is (<= (.-bottom bounds) (.-bottom parent))
                    "The native child actually escapes its initial clipped transform"))
              (finally (close!) (.remove style)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest continuous-visibility-can-opt-out-of-settled-history-restoration
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context
                original js/IntersectionObserver
                *callback (atom nil)
                observer #js {:observe (fn [_]), :disconnect (fn [])}]
            (try
              (restore/begin! {:back? true})
              (set! js/IntersectionObserver (fn [callback _] (reset! *callback callback) observer))
              (await! (render! root [<seen> {:seen {:threshold 0, :restore? false}}]))
              (@*callback #js [#js {:isIntersecting true, :intersectionRatio 0.1}] observer)
              (await! (flush!))
              (is (.contains (.-classList (.-firstElementChild element)) "appeared"))
              (@*callback #js [#js {:isIntersecting false, :intersectionRatio 0}] observer)
              (await! (flush!))
              (is (not (.contains (.-classList (.-firstElementChild element)) "appeared"))
                  "Continuous background animation owners still pause after a history return")
              (finally (close!) (reset! restore/*context context)
                       (set! js/IntersectionObserver original)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest offscreen-appear-waits-for-the-viewport-and-enters-only-once
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                original js/IntersectionObserver
                *callback (atom nil)
                *observer (atom nil)
                *disconnects (atom 0)]
            (try
              (set! js/IntersectionObserver
                    (fn [callback options]
                      (is (= 0 (.-threshold options)))
                      (reset! *callback callback)
                      (reset! *observer #js {:observe (fn [_])
                                             :disconnect #(swap! *disconnects inc)})))
              (await! (render! root [<appear> {:props {:style {:position "fixed", :top "200vh"}}}]))
              (await! (tick! 120))
              (is (some? @*callback))
              (is (not (.contains (.-classList (.-firstElementChild element)) "appeared")))
              (@*callback #js [#js {:isIntersecting true, :intersectionRatio 0.1}] @*observer)
              (await! (flush!))
              (is (.contains (.-classList (.-firstElementChild element)) "appeared"))
              (is (= 1 @*disconnects) "Automatic viewport entrances stop observing after entry")
              (@*callback #js [#js {:isIntersecting false, :intersectionRatio 0}] @*observer)
              (await! (flush!))
              (is (.contains (.-classList (.-firstElementChild element)) "appeared"))
              (finally (close!) (set! js/IntersectionObserver original)))))
        (.catch #(is false (str %)))
        (.finally done))))
(defc <domain-map> {:features [:props]} [{:keys [title]}] [:p title])
(defc <destructured-spec> {:features [:props]} [{:keys [props]}] [:p (:title props)])
(deftest high-frame-rate-hint-is-optional-and-preserves-animation-timing
  (let [supported #js {:frameRate "auto", :playbackRate 1, :currentTime 50}
        unsupported #js {:playbackRate 1}
        rejected #js {}]
    (js/Object.defineProperty rejected "frameRate"
      #js {:get (fn [] "auto")
           :set (fn [_] (throw (js/Error. "Frame-rate hint rejected")))})
    (r/with-framework-bindings [motion/reduced-motion? (constantly false)]
      (motion/prefer-high-frame-rate! [unsupported rejected supported]))
    (is (= "highest" (.-frameRate supported))
        "A rejected hint does not stop the remaining page layer")
    (is (= 1 (.-playbackRate supported)))
    (is (= 50 (.-currentTime supported)))
    (is (not (.hasOwnProperty unsupported "frameRate"))
        "Unsupported browsers receive no inert expando property")
    (set! (.-frameRate supported) "auto")
    (r/with-framework-bindings [motion/reduced-motion? (constantly true)]
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
                     (is (= [0 0.2 0.4 0.6 0.8 1] (vec (.-threshold options))))
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
(deftest seen-slide-settles-at-the-viewport-boundary-and-can-reenter
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                stylesheet (.createElement js/document "link")
                *changes (atom 0)
                observer (js/MutationObserver. #(swap! *changes inc))
                form (fn [top]
                       [<seen> {:seen {:class "slide-in", :restore? false}
                                :props {:style {:position "fixed"
                                                :top top
                                                :height "10rem"
                                                :width "12rem"}}}])]
            (try
              (await! (js/Promise.
                       (fn [resolve reject]
                         (set! (.-rel stylesheet) "stylesheet")
                         (set! (.-href stylesheet) "/css/website/main.min.css")
                         (set! (.-onload stylesheet) resolve)
                         (set! (.-onerror stylesheet) reject)
                         (.appendChild (.-head js/document) stylesheet))))
              ;; Sliding from 68% to 48% visible used to reverse forever at 50%.
              (await! (render! root (form "-5.2rem")))
              (let [node (.-firstElementChild element)]
                (.observe observer node #js {:attributes true, :attributeFilter #js ["class"]})
                (await! (tick! 800))
                (is (.contains (.-classList node) "appeared"))
                (is (= "none" (.-transform (js/getComputedStyle node))))
                (reset! *changes 0)
                (await! (tick! 600))
                (is (zero? @*changes) "The stationary viewport cannot reverse its own slide")
                ;; Exit ends at 52% visible: it must not immediately re-enter.
                (await! (render! root (form "-6.8rem")))
                (await! (tick! 800))
                (is (not (.contains (.-classList node) "appeared")))
                (reset! *changes 0)
                (await! (tick! 600))
                (is (zero? @*changes) "The reverse slide also settles")
                (await! (render! root (form "-3rem")))
                (await! (tick! 800))
                (is (.contains (.-classList node) "appeared"))
                (is (= "none" (.-transform (js/getComputedStyle node))))
                (is (identical? node (.-firstElementChild element))))
              (finally
                (.disconnect observer)
                (close!)
                (.remove stylesheet)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

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
                                  [:> r/strict-mode
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
(deftest document-motion-retains-its-clock-through-hydration-and-spa-mounts-enter
  (async
    done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context]
            (try (restore/begin! {:hydrate? true})
                 (await! (render! root (with-meta [<item> {:id 1, :title "SSR"}] {:key :initial})))
                 (let [node (.querySelector element "button")]
                   (is (= "true" (.getAttribute node "data-document-appear")))
                   (is (.contains (.-classList node) "appeared"))
                   (restore/hydrated!)
                   (await!
                     (render! root (with-meta [<item> {:id 1, :title "Hydrated"}] {:key :initial})))
                   (is (identical? node (.querySelector element "button")))
                   (is (= "true" (.getAttribute node "data-document-appear")))
                   (is (.contains (.-classList node) "appeared")))
                 (restore/navigate! "/another-page")
                 (await! (render! root (with-meta [<item> {:id 2, :title "SPA"}] {:key :spa})))
                 (let [node (.querySelector element "button")]
                   (is (nil? (.getAttribute node "data-document-appear")))
                   (is (not (.contains (.-classList node) "appeared"))))
                 (restore/begin! {:back? true})
                 (await! (render! root (with-meta [<item> {:id 3, :title "Back"}] {:key :back})))
                 (let [node (.querySelector element "button")]
                   (is (nil? (.getAttribute node "data-document-appear")))
                   (is (.contains (.-classList node) "appeared")))
                 (finally (close!) (reset! restore/*context context)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
(defc <remembered-item>
  {:features [[:appear {:class "opacity" :remember-key :motion-test/remembered}]]}
  []
  [:div {:data-remembered true} "Previously visible"])

(deftest remembered-state-preserves-network-ssr-clock-including-selective-hydration
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context
                restore-db! (r/make-restore-fn)]
            (try
              (r/dispatch-sync [:component-motion/seen :motion-test/remembered])
              (restore/begin! {:hydrate? true})
              (await! (render! root [<remembered-item>]))
              (let [node (.querySelector element "[data-remembered]")]
                (is (= "true" (.getAttribute node "data-document-appear")))
                (restore/hydrated!)
                (await! (render! root [<remembered-item>]))
                (is (identical? node (.querySelector element "[data-remembered]")))
                (is (= "true" (.getAttribute node "data-document-appear"))))
              (await! (render! root nil))
              (await! (render! root
                         [:r> (r/context-provider restore/hydrating-context) #js {:value true}
                          [<remembered-item>]]))
              (is (= "true" (.getAttribute (.querySelector element "[data-remembered]") "data-document-appear"))
                  "A retained nested SSR boundary still has the original document clock")
              (await! (render! root nil))
              (await! (render! root [<remembered-item>]))
              (is (nil? (.getAttribute (.querySelector element "[data-remembered]") "data-document-appear"))
                  "A later remembered SPA mount stays settled")
              (finally (close!) (reset! restore/*context context) (restore-db!)))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest remembered-content-is-visible-on-its-next-mount
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context]
            (try
              (restore/navigate! "/motion-test")
              (r/dispatch [:component-motion/seen :motion-test/remembered])
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

(r/reg-event-db :test/visible-owner
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

(deftest initial-network-seen-owners-resume-viewport-motion
  (async done
    (-> (go-promise
          (let [{:keys [root element close!]} (await! (fixture))
                context @restore/*context
                original js/IntersectionObserver
                *callback (atom nil)
                *observed (atom nil)
                observer #js {:observe #(reset! *observed %), :disconnect (fn [])}]
            (try
              (restore/begin! {:hydrate? true})
              (set! js/IntersectionObserver (fn [callback _] (reset! *callback callback) observer))
              (await! (render! root [<seen> {:seen {:class "zoom-x"}}]))
              (let [node (.querySelector element "div")]
                (is (identical? node @*observed))
                (is (.contains (.-classList node) "appeared") "Already-visible SSR stays visible")
                (@*callback #js [#js {:isIntersecting false, :intersectionRatio 0}] observer)
                (await! (flush!))
                (is (not (.contains (.-classList node) "appeared")) "Offscreen content waits for its viewport entrance")
                (restore/hydrated!)
                (@*callback #js [#js {:isIntersecting true, :intersectionRatio 1}] observer)
                (await! (flush!))
                (is (.contains (.-classList node) "appeared"))
                (is (identical? node (.querySelector element "div"))))
              (finally (close!) (set! js/IntersectionObserver original) (reset! restore/*context context)))))
        (.catch #(is false (str %)))
        (.finally done))))


(defc <return-hydration-list> [committed!]
  (r/use-layout-effect (fn [] (committed!) nil) [])
  [:<>
   [:button {:data-remove-return-item true
             :on-click #(r/dispatch [:state [:component-motion-return-items] []])}
    "Remove item"]
   [<list> @(r/subscribe [:state [:component-motion-return-items]])]])

(deftest network-return-hydrates-the-original-motion-contract-and-retires-it-before-exit
  (async done
    (let [element (.createElement js/document "div")
          stylesheet (.createElement js/document "link")
          document-root (.-documentElement js/document)
          before-context @restore/*context
          before-return (.getAttribute document-root "data-document-return")
          before-settling (.getAttribute document-root "data-return-settling")
          before-feature motion/motion-feature
          before-error (.-error js/console)
          before-warn (.-warn js/console)
          before-batching (r/batching-flush)
          restore-db! (r/make-restore-fn)
          *root (atom nil)
          *committed? (atom false)
          *contracts (atom [])
          *warnings (atom [])
          *recoveries (atom [])
          *starts (atom [])
          item {:id "native-network-return", :title "Retained server item"}
          committed! #(reset! *committed? true)
          form [<return-hydration-list> committed!]
          capture-console! (fn [original args]
                             (let [message (string/join " " (filter string? args))]
                               (when (re-find #"(?i)hydrat|server.rendered|did not match|didn't match" message)
                                 (swap! *warnings conj message)))
                             (.apply original js/console (to-array args)))
          record-start! (fn [event]
                          (when (= "native-network-return" (.getAttribute (.-target event) "data-item"))
                            (swap! *starts conj {:type (.-type event)
                                                :property (.-propertyName event)
                                                :marker (.getAttribute (.-target event) "data-document-appear")})))]
      ;; Instrument the actual motion render contract. A stale server DOM
      ;; attribute alone cannot establish that React's first props matched it.
      (set! motion/motion-feature
        (assoc before-feature :render
               (fn [original-form options state]
                 (let [result ((:render before-feature) original-form options state)
                       attrs (second (:form result))]
                   (when (= (:id item) (:data-item attrs))
                     (swap! *contracts conj {:server? (boolean (:server? options))
                                             :exiting? (= :exiting (get-in options [:presence :phase]))
                                             :marker (:data-document-appear attrs)}))
                   result))))
      (set! (.-error js/console) (fn [& args] (capture-console! before-error args)))
      (set! (.-warn js/console) (fn [& args] (capture-console! before-warn args)))
      (.addEventListener element "animationstart" record-start! true)
      (.addEventListener element "transitionrun" record-start! true)
      (.appendChild (.-body js/document) element)
      (-> (go-promise
            (await! (js/Promise.
                      (fn [resolve reject]
                        (set! (.-rel stylesheet) "stylesheet")
                        (set! (.-href stylesheet) "/css/website/main.min.css")
                        (set! (.-onload stylesheet) #(resolve nil))
                        (set! (.-onerror stylesheet)
                              #(reject (js/Error. "Missing network-return motion CSS")))
                        (.appendChild (.-head js/document) stylesheet))))
            ;; Generate the real server contract before installing the native
            ;; document-return policy, just as a network response is produced.
            (.removeAttribute document-root "data-document-return")
            (.removeAttribute document-root "data-return-settling")
            (r/dispatch-sync [:state [:component-motion-return-items] [item]])
            (restore/begin! {:hydrate? true})
            (let [markup (binding [context/*server?* true] (r/render-to-string form))]
              (set! (.-innerHTML element) markup))
            (let [server-node (.querySelector element "[data-item]")]
              (is (= "true" (.getAttribute server-node "data-document-appear")))
              (is (some #(and (:server? %) (true? (:marker %))) @*contracts))
              ;; Only the permanent return policy is needed here. Head-policy
              ;; first-input/temporary-guard release has its own native checks;
              ;; this test must not replace that adapter with a copied listener.
              (.setAttribute document-root "data-document-return" "true")
              (reset! *starts [])
              (reset! *root (r/hydrate-root element form
                             {:on-recoverable-error #(swap! *recoveries conj (.-message %))}))
              (await! (support/wait-for! #(deref *committed?) "Returned motion owner did not hydrate"))
              (restore/hydrated!)
              (await! (support/settle!))
              (is (identical? server-node (.querySelector element "[data-item]"))
                  "Network-return hydration retains the actual server node")
              (let [first-client (first (remove :server? @*contracts))]
                (is (some? first-client))
                (is (true? (:marker first-client))
                    "The first React motion props retain the server marker; stale DOM is insufficient"))
              (is (empty? @*recoveries) (str "Native hydration recoveries: " (pr-str @*recoveries)))
              (is (empty? @*warnings) (str "Native hydration attribute warnings: " (pr-str @*warnings)))
              (is (empty? @*starts) "Returned SSR does not replay an entrance clock")
              (is (empty? (array-seq (.getAnimations server-node)))
                  "The actual return stylesheet settles the retained entrance")
              (is (= "1" (.-opacity (js/getComputedStyle server-node))))
              ;; Exercise ordinary declarative intent -> re-frame event ->
              ;; presence removal. Do not replace the owner or force its styles.
              (.click (.querySelector element "[data-remove-return-item]"))
              (await! (support/wait-for! #(some (fn [contract] (:exiting? contract)) @*contracts)
                                        "Presence did not receive the ordinary removal event"))
              (is (identical? server-node (.querySelector element "[data-item]"))
                  "The real owner stays mounted for its CSS exit")
              (await! (support/wait-for! #(some (fn [start] (= "transitionrun" (:type start))) @*starts)
                                        "Returned SSR item did not start its native opacity exit"))
              (is (nil? (.getAttribute server-node "data-document-appear"))
                  "React retires the SSR marker before ordinary exit")
              (is (every? #(nil? (:marker %)) @*starts)
                  "No exit clock starts beneath the persistent return override")
              (is (some #(and (= "transitionrun" (:type %)) (= "opacity" (:property %))) @*starts))
              (await! (support/wait-for! #(nil? (.querySelector element "[data-item]"))
                                        "Native CSS exit did not dispose the retained owner"))))
          (.catch #(is false (str %)))
          (.finally
            (fn []
              (when @*root (r/unmount @*root))
              (.removeEventListener element "animationstart" record-start! true)
              (.removeEventListener element "transitionrun" record-start! true)
              (.remove element)
              (.remove stylesheet)
              (set! motion/motion-feature before-feature)
              (set! (.-error js/console) before-error)
              (set! (.-warn js/console) before-warn)
              (r/set-batching-flush! before-batching)
              (reset! restore/*context before-context)
              (doseq [[attribute value] [["data-document-return" before-return]
                                         ["data-return-settling" before-settling]]]
                (if (nil? value) (.removeAttribute document-root attribute)
                  (.setAttribute document-root attribute value)))
              (restore-db!)
              (done)))))))
