(ns website.modules.home.layout
  (:require [website.component.registry]
            [website.macros :as m :include-macros true]
            [website.react :as r]
            [clojure.string :as string]
            [website.util :as util]
            [website.components.image :as img]
            [website.content.fields :as fields]))

(m/defc ^:private <inset-image>
  {:features [[:seen "zoom"]]}
  [img-attr zoomed?]
  [:div [img/<picture>
         (with-meta (merge {:loading "lazy"
                 :sizes (if @zoomed? "80vw" "(max-width: 37.5em) 9.375rem, 35vw")}
                img-attr
                {:class "media image-inset"
                 :on-click #(r/rswap! zoomed? not)}) (meta img-attr))]])

(m/defc <float-img> "Needs to go within a float-wrapper..."
  [id img-attr & [caption pos caption-owner caption-path]]
  (let [zoomed? (r/atom false)]
    (fn [id img-attr & [caption pos caption-owner caption-path]]
      [:figure.float-with-caption
       {:id id :class (or pos "left")
        :style (when @zoomed?
                 {:width "80%" ; TODO nvm not hardcoding and not going crazy large when vw high, should be based on img size so don't blow up too much anyways
                  :margin "var(--space-lg) 10%"}) }
       [<inset-image> img-attr zoomed?]
       (when caption
         (if caption-owner [fields/<text> :figcaption caption-owner caption-path]
           [:figcaption caption]))])))

(m/defc ^:private <story-line>
  {:features [[:seen "slide-in"]]}
  [line content index]
  [:div [fields/<text> :span content [:text] nil index]])

(m/defc <auto-layout-text-imgs> "Take text and images and space out floats appropriately. Pretty dumb but eh"
  [content]
  (let [text-part (for [[i line] (map-indexed vector (string/split-lines (:text content)))]
                    [:<>
                     [<story-line> line content i]
                     [:br]])
        chunk-size (max 1 (int (/ (count text-part)
                                  (max 1 (count (:images content))))))
        image-parts (map-indexed
                      (fn [i [id image caption pos]]
                        [<float-img> (str (or (:identity content) "story") "-image-" id)
                         image caption pos content [:images i 2]])
                      (:images content))
        text-parts (map #(into [:div] %)
                        (partition chunk-size chunk-size (repeat "") text-part))
        result (map-indexed
                 (fn [i part] (with-meta part {:key (str "auto-layout-part-" i)}))
                 (apply util/interleave-all (filter seq [image-parts text-parts])))]
     [:div.float-wrapper result]))


