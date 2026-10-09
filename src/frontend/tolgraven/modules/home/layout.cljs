(ns tolgraven.modules.home.layout
  (:require [tolgraven.component.registry]
            [tolgraven.macros :as m :include-macros true]
            [reagent.core :as r]
            [clojure.string :as string]
            [tolgraven.util :as util]
            [tolgraven.components.image :as img]))

(m/defc ^:private <inset-image>
  {:features [[:seen "zoom"]]}
  [img-attr zoomed?]
  [:div [img/<picture>
         (merge {:loading "lazy"
                 :sizes (if @zoomed? "80vw" "(max-width: 37.5em) 9.375rem, 35vw")}
                img-attr
                {:class "media image-inset"
                 :on-click #(r/rswap! zoomed? not)})]])

(m/defc <float-img> "Needs to go within a float-wrapper..."
  [id img-attr & [caption pos]]
  (let [zoomed? (r/atom false)]
    (fn [id img-attr & [caption pos]]
      [:figure.float-with-caption
       {:id id :class (or pos "left")
        :style (when @zoomed?
                 {:width "80%" ; TODO nvm not hardcoding and not going crazy large when vw high, should be based on img size so don't blow up too much anyways
                  :margin "var(--space-lg) 10%"}) }
       [<inset-image> img-attr zoomed?]
       (when caption [:figcaption caption])])))

(m/defc ^:private <story-line>
  {:features [[:seen "slide-in"]]}
  [line]
  [:div [:span line]])

(m/defc <auto-layout-text-imgs> "Take text and images and space out floats appropriately. Pretty dumb but eh"
  [content]
  (let [text-part (for [line (string/split-lines (:text content))]
                    [:<>
                     [<story-line> line]
                     [:br]])
         chunk-size (int (/ (count text-part)
                            (count (:images content))))
         result (->> (util/interleave-all (map (fn [[id & args]] (into [<float-img> (str "story-image-" id)] args))
                                               (:images content))
                                          (map #(into [:div] %)
                                               (partition chunk-size chunk-size
                                                          (repeat "") text-part)))
                      (map-indexed (fn [i v]
                                     (with-meta
                                      v {:key (str "auto-layout-part-" i)}))))] ;would need a parent id thingy as well tho
     [:div.float-wrapper
      result]))


