(ns website.media-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require
    [cljs.test :refer-macros [deftest is testing async]]
    [clojure.string :as string]
    [website.modules.instagram.views :as instagram]
    [website.modules.instagram.events]
    [website.modules.instagram.subs]
    [website.events]
    [website.subs]
    [website.test-support :as support]
    [website.react :as r]
    [website.components.media :as media]
    [website.components.image :as image]
    [website.components.video :as video]))

(defn- render-element [component]
  (let [element (.createElement js/document "div")]
    (set! (.-innerHTML element) (r/render-to-static-markup component))
    element))

(deftest responsive-picture-preserves-uppercase-original-and-selects-sized-variants
  (let [element (render-element [image/<picture> {:src "img/crowd-lbp.JPG"}])
        img (.querySelector element "img")
        avif (.querySelector element "picture source[type='image/avif']")]
    (is (= "img/crowd-lbp.JPG" (.getAttribute img "src")))
    (is (= "540" (.getAttribute img "width")))
    (is (.includes (.getAttribute avif "srcset") "img/crowd-lbp-400w.avif 400w"))))

(deftest interlude-media-type
  (testing "an image interlude renders an image, not an undecodable video"
    (let [element (render-element
                    [media/<interlude> [{:bg {:src "img/collage-strips.jpg"}}] 0])
          image (.querySelector element "img.media-as-bg")]
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

(deftest pending-instagram-uses-native-logos-before-a-ready-feed-replaces-them
  (async done
    (let [restore! (r/make-restore-fn)
          container (.createElement js/document "div")
          *root (atom nil)
          *sources (atom [])
          *errors (atom [])
          images (fn [] (vec (array-seq (.querySelectorAll container ".instagram-item img"))))
          record-source! (fn [node]
                           (when (= 1 (.-nodeType node))
                             (doseq [image (if (= "IMG" (.-tagName node))
                                            [node]
                                            (array-seq (.querySelectorAll node "img")))]
                               (swap! *sources conj (.getAttribute image "src")))))
          record-error! (fn [event]
                          (when (= "IMG" (.. event -target -tagName))
                            (swap! *errors conj (.getAttribute (.-target event) "src"))))
          observer (js/MutationObserver.
                     (fn [records]
                       (doseq [record (array-seq records)]
                         (if (= "attributes" (.-type record))
                           (record-source! (.-target record))
                           (doseq [node (array-seq (.-addedNodes record))]
                             (record-source! node))))))]
      (.appendChild (.-body js/document) container)
      (.addEventListener container "error" record-error! true)
      (.observe observer container #js {:childList true, :subtree true, :attributes true, :attributeFilter #js ["src"]})
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:instagram/store-posts {:posts nil}])
            (reset! *root (await! (support/create-root! container)))
            (await! (support/render! @*root [instagram/<instagram>]))
            (await! (support/wait-for!
                      #(and (= 24 (count (images)))
                            (every? (fn [image] (and (.-complete image) (pos? (.-naturalWidth image)))) (images)))
                      "Pending Instagram logos did not decode"))
            ;; Keep the initial native src mutations: an HTTP400 followed by
            ;; the view's error-driven global fallback must not make this pass.
            (is (= 24 (count (images))))
            (is (every? #(= "img/logo/instagram-fallback-logo.png" (.getAttribute % "src")) (images)))
            (is (not-any? #(or (string/includes? (or % "") "url=null")
                              (string/includes? (or % "") "url=undefined")) @*sources)
                "A pending range entry never requests an invalid image proxy URL")
            (is (empty? @*errors) "Native pending logos do not depend on image errors to appear")
            (r/dispatch-sync [:instagram/store-posts
                              {:posts {"ready-media" {:id "ready-media"
                                                      :timestamp "2026-10-17T12:00:00Z"
                                                      :caption "Native ready image"
                                                      :media_url "/img/tolgrav-square.png"}}}])
            (await! (support/wait-for!
                      #(and (= 1 (count (images)))
                            (= "/img/tolgrav-square.png" (.getAttribute (first (images)) "src"))
                            (.-complete (first (images)))
                            (pos? (.-naturalWidth (first (images)))))
                      "Ready Instagram media did not replace the pending logos"))
            (is (= "Native ready image" (.-alt (first (images)))))
            (is (empty? @*errors) "Ready native media keeps its existing direct URL preference"))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (.disconnect observer)
                      (.removeEventListener container "error" record-error! true)
                      (when @*root (support/unmount! @*root))
                      (.remove container)
                      (restore!)
                      (done)))))))
