(ns tolgraven.components.video
  "Helpers for serving modern video formats (VP9, AV1) with automatic fallbacks"
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [clojure.string :as string]
    [reagent.core :as r]
    [tolgraven.components.image :as img]))

(defn- replace-extension
  "Replace file extension and add codec suffix.
   e.g., 'media/video.mp4' -> 'media/video-vp9.webm'"
  [path codec]
  (let [base (string/replace path #"\.(mp4|mov|avi)$" "")]
    (str base "-" codec ".webm")))

(defn- should-use-modern-formats?
  "Determine if we should generate modern format sources for this video.
   Skip for:
   - External URLs (we don't control those assets)
   - URLs with query strings (likely already proxied/optimized)
   - Streaming URLs (blob:, data:)
   Only for local MP4/MOV files."
  [src]
  (and (string? src)
       (re-find #"\.(mp4|mov)$" src)
       (not (re-find #"^(https?:|//|blob:|data:)" src))    ;; Skip external/blob/data URLs
       (not (re-find #"\?" src))))

(defc <video>
  "Generate a <video> element with WebM (VP9/AV1) sources and fallback to original.

   Usage:
     [video {:src 'media/clip.mp4' :loop true :muted true}]
     [video {:src 'media/clip.mp4' :poster 'img/poster.jpg' :controls true}]

   Generates:
     <video loop muted poster='img/poster.jpg'>
       <source src='media/clip-av1.webm' type='video/webm; codecs=av01.0.05M.08'>
       <source src='media/clip-vp9.webm' type='video/webm; codecs=vp9'>
       <source src='media/clip.mp4' type='video/mp4'>
     </video>

   Note: The :poster attribute uses the original image path. For optimized posters
   with WebP/AVIF support, use [video-with-picture-poster] instead.

   Browsers automatically select the first format they support."
  [{:keys [src] :as attrs}]
  (if (should-use-modern-formats? src)
    (let [video-attrs (dissoc attrs :src)] ; Remove :src from video tag, it goes in sources
      [:video video-attrs
       ;; AV1 - best compression, Chrome 90+, Firefox 93+, Safari 17+
       [:source {:src (replace-extension src "av1")
                 :type "video/webm; codecs=av01.0.05M.08"}]
       ;; VP9 - good compression, Chrome, Firefox, Edge, Safari 14.1+
       [:source {:src (replace-extension src "vp9")
                 :type "video/webm; codecs=vp9"}]
       ;; H.264 MP4 - universal fallback
       [:source {:src src
                 :type "video/mp4"}]])
    ;; No modern format available or non-MP4 source, use video with src directly
    [:video attrs
     [:source {:src src}]]))

(defc <video-with-picture-poster>
  "Generate a video with an optimized poster image using modern formats.

   The poster is rendered as an overlay using <picture> element, providing WebP/AVIF
   support that the HTML5 video poster attribute doesn't support natively.

   The poster image is hidden once the video starts playing.

   Usage:
     [video-with-picture-poster
       {:src 'media/clip.mp4' :loop true :muted true}
       {:poster 'media/clip.jpg' :alt 'Video preview'}]

   Generates sibling elements in the caller's containing block:
   - An optimized <picture> element as poster (uses WebP/AVIF)
   - The <video> element with modern format sources

   Keeps the native poster as a fallback and hides the overlay on playback."
  [video-attrs {:keys [poster alt] :as poster-attrs}]
  (r/with-let [*playing-src (r/atom nil)]
    (let [src (:src video-attrs)
          poster (or poster (:poster video-attrs))
          on-playing (or (:on-playing video-attrs) (:onPlaying video-attrs))]
      ;; A positioned wrapper collapses around absolute children and moves the
      ;; background out of its section's containing block.
      [:<>
       (when (and poster (not= src @*playing-src))
         [img/<picture>
          (merge {:src poster
                  :alt (or alt "Video poster")
                  :class "video-poster-image"}
                 (select-keys poster-attrs [:class])
                 {:style (merge {:position "absolute"
                                 :top 0
                                 :left 0
                                 :width "100%"
                                 :height "100%"
                                 :object-fit "cover"
                                 :z-index 1
                                 :pointer-events "none"}
                                (:style poster-attrs))})])
       [<video> (-> video-attrs
                  (dissoc :on-playing)
                  (assoc :poster poster
                         :onPlaying (fn [event]
                                      (reset! *playing-src src)
                                      (when on-playing (on-playing event)))))]])))

(defc <media-as-bg>
  "Generate video element optimized for use as background media.
   Adds common background styling attributes and uses poster optimization."
  [{:keys [src poster class] :as attrs}]
  (let [combined-attrs (merge attrs
                              {:class (str "media media-as-bg " (or class ""))})]
    (if poster
      [<video-with-picture-poster> combined-attrs {:poster poster}]
      [<video> combined-attrs])))

;; For backward compatibility - export main functions
(def ^:export modernVideo <video>)
