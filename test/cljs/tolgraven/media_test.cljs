(ns tolgraven.media-test
  (:require
    [cljs.test :refer-macros [deftest is testing]]
    [reagent.dom.server :as server]
    [tolgraven.components.media :as media]
    [tolgraven.components.image :as image]
    [tolgraven.components.video :as video]))

(defn- render-element [component]
  (let [element (.createElement js/document "div")]
    (set! (.-innerHTML element) (server/render-to-static-markup component))
    element))

(deftest responsive-picture-preserves-uppercase-original-and-selects-sized-variants
  (let [element (render-element [image/<picture> {:src "img/crowd-lbp.JPG"}])
        img (.querySelector element "img")
        avif (.querySelector element "source[type='image/avif']")]
    (is (= "img/crowd-lbp.JPG" (.getAttribute img "src")))
    (is (= "540" (.getAttribute img "width")))
    (is (.includes (.getAttribute avif "srcset") "img/crowd-lbp-400w.avif 400w"))))

(deftest interlude-media-type
  (testing "an image interlude renders an image, not an undecodable video"
    (let [element (render-element
                    [media/<interlude> [{:bg {:src "img/collage-strips.jpg"}}] 0])
          image (.querySelector element "img")]
      (is (nil? (.querySelector element "video")))
      (is (= "img/collage-strips.jpg" (.getAttribute image "src")))
      (is (.contains (.-classList image) "media-as-bg")))))

(deftest interlude-video-background
  (let [element (render-element
                  [media/<interlude> [{:bg {:src "media/fog-3d-small.mp4"
                                         :poster "media/fog-3d-small.jpg"}}] 0])
        video (.querySelector element "video")
        poster (.querySelector element "picture img")]
    (is (= "SECTION" (.-tagName (.-parentElement video)))
        "The section positions the background; no collapsing wrapper intervenes")
    (is (.contains (.-classList video) "media-as-bg"))
    (is (= "media/fog-3d-small.jpg" (.getAttribute video "poster")))
    (is (= "media/fog-3d-small.jpg" (.getAttribute poster "src")))
    (is (= "100%" (.. poster -style -width))
        "Custom poster styles preserve the default cover geometry")))

(deftest video-preserves-caller-attributes
  (let [element (render-element
                  [video/<video-with-picture-poster>
                   {:src "media/fog-3d-small.mp4"
                    :poster "media/fog-3d-small.jpg"
                    :class "media-as-bg"
                    :style {:width "80%" :object-fit "contain"}}
                   nil])
        video (.querySelector element "video")]
    (is (= "80%" (.. video -style -width)))
    (is (= "contain" (.. video -style -objectFit)))
    (is (= "media/fog-3d-small.jpg"
           (.getAttribute (.querySelector element "img") "src")))
    (is (nil? (.querySelector element ".video-with-poster-wrapper")))))
