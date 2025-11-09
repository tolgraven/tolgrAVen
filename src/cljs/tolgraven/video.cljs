(ns tolgraven.video
  "Helpers for serving modern video formats (VP9, AV1) with automatic fallbacks"
  (:require
   [clojure.string :as string]
   [tolgraven.image :as img]))

(defn- replace-extension
  "Replace file extension and add codec suffix.
   e.g., 'media/video.mp4' -> 'media/video-vp9.webm'"
  [path codec]
  (let [base (string/replace path #"\.(mp4|mov|avi)$" "")]
    (str base "-" codec ".webm")))

(defn- should-use-modern-formats?
  "Determine if we should generate modern format sources for this video.
   Only for MP4 files currently."
  [src]
  (and (string? src)
       (re-find #"\.(mp4|mov)$" src)))

(defn video
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

(defn video-with-attrs
  "Convenience wrapper that merges attributes into video element.
   Useful when you need to add attributes conditionally or from multiple sources."
  [base-attrs dynamic-attrs]
  [video (merge base-attrs dynamic-attrs)])

(defn get-src-variants
  "Get all available format variants for a video path.
   Returns a map with :original, :vp9, and :av1 paths.
   Useful for preloading or manual format selection."
  [src]
  (if (should-use-modern-formats? src)
    {:original src
     :vp9 (replace-extension src "vp9")
     :av1 (replace-extension src "av1")}
    {:original src}))

(defn preload-link
  "Generate a <link rel='preload'> for a video with modern format support.
   Preloads the best format the browser supports."
  [src]
  (let [variants (get-src-variants src)]
    (if (contains? variants :av1)
      ;; For modern browsers, create preload hints for all formats
      ;; Browser will only fetch the one it supports
      [:<>
       [:link {:rel "preload"
               :as "video"
               :href (:av1 variants)
               :type "video/webm; codecs=av01.0.05M.08"}]
       [:link {:rel "preload"
               :as "video"
               :href (:vp9 variants)
               :type "video/webm; codecs=vp9"}]
       [:link {:rel "preload"
               :as "video"
               :href (:original variants)
               :type "video/mp4"}]]
      ;; Fallback: just preload the original
      [:link {:rel "preload"
              :as "video"
              :href (:original variants)}])))

(defn video-with-picture-poster
  "Generate a video with an optimized poster image using modern formats.

   The poster is rendered as an overlay using <picture> element, providing WebP/AVIF
   support that the HTML5 video poster attribute doesn't support natively.

   The poster image is hidden once the video starts playing.

   Usage:
     [video-with-picture-poster
       {:src 'media/clip.mp4' :loop true :muted true}
       {:poster 'media/clip.jpg' :alt 'Video preview'}]

   Generates a wrapper div containing:
   - An optimized <picture> element as poster (uses WebP/AVIF)
   - The <video> element with modern format sources

   The video should have appropriate event handlers to hide the poster on play."
  [video-attrs {:keys [poster alt] :as poster-attrs}]
  (let [poster-only-attrs (select-keys poster-attrs [:poster :alt :class :style])]
    [:div.video-with-poster-wrapper
     {:style {:position "relative"}}
     ;; Optimized poster image using picture element
     (when poster
       [img/picture (merge {:src poster
                           :alt (or alt "Video poster")
                           :class "video-poster-image"
                           :style {:position "absolute"
                                   :top 0
                                   :left 0
                                   :width "100%"
                                   :height "100%"
                                   :object-fit "cover"
                                   :z-index 1
                                   :pointer-events "none"}}
                          (select-keys poster-attrs [:class :style]))])
     ;; Video element with modern formats
     [video (dissoc video-attrs :poster)]]))

(defn infer-poster
  "Infer poster image path from video path.
   E.g., 'media/video.mp4' -> 'media/video.jpg'
   Returns nil if no poster can be inferred."
  [video-src]
  (when video-src
    (string/replace video-src #"\.(mp4|mov|avi)$" ".jpg")))

(defn video-with-auto-poster
  "Convenience wrapper that automatically infers poster path from video path.

   Usage:
     [video-with-auto-poster {:src 'media/fog-3d-small.mp4' :loop true}]

   Automatically looks for 'media/fog-3d-small.jpg' as the poster and uses
   optimized formats (WebP/AVIF) via the picture element."
  [video-attrs]
  (let [poster-path (infer-poster (:src video-attrs))]
    (if poster-path
      [video-with-picture-poster video-attrs {:poster poster-path
                                               :alt "Video preview"}]
      [video video-attrs])))

;; For backward compatibility - export main functions
(def ^:export modernVideo video)
