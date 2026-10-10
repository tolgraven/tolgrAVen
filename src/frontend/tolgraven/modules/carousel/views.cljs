(ns tolgraven.modules.carousel.views
  (:require [tolgraven.component]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.react :as rf]
            [reagent.core :as r]
            [tolgraven.util :as util]))

(m/defc <carousel-idx-btns>
  [id idx-model amount]
  [:div.carousel-idxs
   (doall (for [idx (range amount)]
            ^{:key (str "carousel-" id "-index-btn-" idx)}
            [:button.carousel-btn.carousel-idx
             {:aria-label (str "Show slide " (inc idx))
              :aria-current (when (= @idx-model idx) "true")
              :class (when (= @idx-model idx) "carousel-idx-current")
              :on-click #(rf/dispatch [:carousel/set-index id idx])}
             [:i.fas.fa-circle]]))])

(m/defc <carousel> "Three-showing carousel with zoom up of center item, and animating changes.
                The generic enough stuff could go in a more general carousel-builder
                or we just make two."
  [id :- [:or :keyword :string] options :- :map content :- [:sequential :any]]
  (let [num-items-shown 3
        index (rf/subscribe [:state [:carousel id :index]])
        dec-fn (fn [] ; remove duplication here by only dispatching and index being generic "roll arounder"
                 (rf/dispatch [:carousel/rotate id content :dec]))
        inc-fn (fn []
                 (rf/dispatch [:carousel/rotate id content :inc]))
        moving (rf/subscribe [:state [:carousel id :direction]])
        left-content #(if (pos? %)
                       (get content (dec %))
                       (last content))
        right-content #(if (< % (dec (count content)))
                         (get content (inc %))
                         (first content))]
    (fn [id options content]
      [:div.carousel.carousel-three
       (merge options
              {:id (name id)})

       [:button.carousel-btn.carousel-prev-btn {:on-click dec-fn :aria-label "Previous slide"} "<"]

       [:ul.carousel-items
        [:li.carousel-item-left-pseudo
         {:class @moving}
         (if (pos? (dec @index))
           (get content (dec (dec @index)))
           (last content))]
        [:li.carousel-item-left
         {:class @moving
          :on-click dec-fn}
         (left-content @index)]

        [:li.carousel-item-middle
         {:class @moving}
         (get content @index)]

        [:li.carousel-item-right
         {:class @moving
          :on-click inc-fn}
         (right-content @index)]
        [:li.carousel-item-right-pseudo
         {:class @moving}
         (if (< (inc @index) (dec (count content)))
           (get content (inc (inc @index)))
           (first content))]]

       [:button.carousel-btn.carousel-next-btn {:on-click inc-fn :aria-label "Next slide"} ">"]
       [<carousel-idx-btns> id index (count content)] ])))

(m/defc <carousel-normal> "Don't fuck up with fancy hot swaps for transitions, just stuff everything in."
  [id :- [:or :keyword :string] attrs :- :map content :- [:sequential :any] & {:keys [autoplay seconds-autoplay]
                       :or {seconds-autoplay 10}}]
  (let [index (rf/subscribe [:carousel/index id])
        dec-wrap #(if (neg? (dec %))
                    (dec (count content))
                    (dec %))
        dec-fn #(rf/dispatch [:carousel/rotate id content :dec])
        inc-wrap #(if (< (inc %) (count content))
                    (inc %)
                    0)
        inc-fn #(rf/dispatch [:carousel/rotate id content :inc])
        left-content #(if (pos? %)
                        (get content (dec %))
                        (last content))
        right-content #(if (< % (dec (count content)))
                         (get content (inc %))
                         (first content))
        ref-f #(when %
                 (rf/dispatch [:carousel/set-index id 0])
                 (when autoplay
                   (rf/dispatch [:dispatch-in/set {:ms (* 1000 seconds-autoplay)
                                                   :repeat true
                                                   :k id
                                                   :dispatch [:carousel/rotate id content :inc]}]))     )
        interact-start (atom {:x 0 :y 0})
        handle-interaction (fn [start end]
                             (cond
                              (> (:x start) (:x end))
                              (inc-fn)
                              (< (:x start) (:x end))
                              (dec-fn)))
        left-offset (r/atom 0)
        autoplaying (r/atom autoplay)]
    (fn [id attrs content]
      [:div.carousel.carousel-normal
       (merge attrs
              {:id (name id)
               :ref ref-f})

       [:button.carousel-btn.carousel-prev-btn
        {:on-click dec-fn :aria-label "Previous slide"}
        [:i.fas.fa-angle-left]]

       (into [:ul.carousel-items
              {:on-mouse-enter #(reset! autoplaying false)
               :on-mouse-leave #(when autoplay
                                  (reset! autoplaying true))
               :on-mouse-down #(reset! interact-start
                                       {:x (.-offsetX %)
                                        :y (.-offsetY %)})
               :on-mouse-up (fn [e]
                              (let [interact-end {:x (.-pageX e)
                                                  :y (.-pageY e)}]
                                ; (.stopPropagation e)
                                (handle-interaction @interact-start interact-end)))
               :on-touch-start #(reset! interact-start
                                        {:x (-> % .-changedTouches first .-screenX)
                                         :y (-> % .-changedTouches first .-screenY)})
               :on-touch-end (fn [e]
                               (let [interact-end {:x (-> e .-changedTouches first .-screenX)
                                                   :y (-> e .-changedTouches first .-screenY)}]
                                 ; (.stopPropagation e)
                                 (handle-interaction @interact-start interact-end)
                                 (reset! left-offset 0)))
               :on-touch-move (fn [e]
                                (let [interact-pos {:x (-> e .-changedTouches first .-screenX)
                                                    :y (-> e .-changedTouches first .-screenY)}]
                                 (reset! left-offset (util/px-to-rem (- (:x interact-pos) (:x @interact-start))))))}]
             (doall
              (map-indexed
               (fn [i item]
                 (with-meta
                  [:li.carousel-item-min
                    {:class (cond
                              (= i @index) "carousel-item-main"
                              (= (inc-wrap i) @index) "carousel-item-prev"
                              (= (dec-wrap i) @index) "carousel-item-next")
                     :style {:left (cond
                                    (= i @index) (str @left-offset "rem")
                                    ; (= (inc-wrap i) @index) (str "calc(-100% +" @left-offset ")")
                                    ; (= (dec-wrap i) @index) (str "calc(100% -" @left-offset ")")
                                    )}}
                    (when (or (= i @index) ; only show (hence init) item when visible or adjacent. needs to be an option though - needed for some stuff but would slow down direct jumps by idx-btns...
                              (= (inc-wrap i) @index)
                              (= (dec-wrap i) @index))
                      item)]
                   {:key (str "carousel-" (name id) "-item-" i)}))
               content)))

       [:button.carousel-btn.carousel-next-btn
        {:on-click inc-fn :aria-label "Next slide"}
        [:i.fas.fa-angle-right]]
       [<carousel-idx-btns> id index (count content)] ])))
