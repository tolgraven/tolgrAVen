(ns tolgraven.video-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [async deftest is]]
            [reagent.dom.server :as server]
            [tolgraven.components.video :as video]
            [tolgraven.test-support :as support]))

(deftest responsive-video-retains-full-size-codec-fallbacks
  (let [html (server/render-to-string
               [video/<video> {:src "media/fog-3d-small.mp4"}])]
    (is (.includes html "media=\"(max-width: 43.75em)\""))
    (is (< (.indexOf html "fog-3d-small-800w-av1.webm")
           (.indexOf html "fog-3d-small-av1.webm")))
    (is (.includes html "fog-3d-small-800w.mp4"))
    (is (.includes html "src=\"media/fog-3d-small.mp4\""))))

(deftest distant-autoplay-video-keeps-its-poster-without-requesting-sources
  (let [html (server/render-to-string
               [video/<video> {:src "/media/clip.mp4"
                               :poster "/img/poster.jpg"
                               :loading "lazy"
                               :autoPlay true}])]
    (is (.includes html "poster="))
    (is (not (.includes html "clip")))
    (is (not (.includes html "<source")))))

(deftest lazy-video-acquires-codec-sources-near-the-viewport-and-releases-observation
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                original js/IntersectionObserver
                *callback (atom nil)
                *observed (atom nil)
                *captured (atom nil)
                *disconnects (atom 0)
                observer #js {:observe #(reset! *observed %)
                              :disconnect #(swap! *disconnects inc)}]
            (.appendChild (.-body js/document) element)
            (try
              (set! js/IntersectionObserver
                    (fn [callback _] (reset! *callback callback) observer))
              (await! (support/render! root
                        [video/<video> {:src "/media/clip.mp4"
                                        :ref #(reset! *captured %)
                                        :loading "lazy"
                                        :autoPlay true}]))
              (is (identical? @*observed (.querySelector element "video")))
              (is (identical? @*observed @*captured) "The caller still receives the native video")
              (is (nil? (.querySelector element "source")))
              (@*callback #js [#js {:isIntersecting false}] observer)
              (await! (support/settle!))
              (is (nil? (.querySelector element "source")))
              (@*callback #js [#js {:isIntersecting true}] observer)
              (await! (support/wait-for! #(.querySelector element "source")))
              (is (= ["/media/clip-av1.webm" "/media/clip-vp9.webm" "/media/clip.mp4"]
                     (mapv #(.getAttribute % "src")
                           (array-seq (.querySelectorAll element "source")))))
              (await! (support/render! root nil))
              (is (nil? @*captured))
              (is (>= @*disconnects 2))
              (finally (support/unmount! root)
                       (.remove element)
                       (set! js/IntersectionObserver original)))))
        (.catch #(is false (str %)))
        (.finally done))))
