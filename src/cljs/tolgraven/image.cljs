(ns tolgraven.image
  "Helpers for serving modern image formats (WebP, AVIF) with automatic fallbacks"
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.image.sources :as sources]
    [tolgraven.react :as react]
    [reagent.core :as r]))

(def get-src-variants sources/get-src-variants)

(defn- modern-source-selected? [image src]
  (let [current-src (.-currentSrc image)]
    (and (seq current-src)
         (not= current-src (.-href (js/URL. src (.-baseURI image)))))))

(defc <picture>
  "Generate a <picture> element with WebP and AVIF sources and fallback to original.

   Usage:
     [picture {:src 'img/photo.jpg' :alt 'My photo'}]
     [picture {:src 'img/photo.jpg' :alt 'My photo' :class 'hero-img'}]

   Generates:
     <picture>
       <source srcset='img/photo.avif' type='image/avif'>
       <source srcset='img/photo.webp' type='image/webp'>
       <img src='img/photo.jpg' alt='My photo' class='hero-img'>
     </picture>

   Browsers select the first supported format. If requesting or decoding that
   source fails, retry the original JPEG/PNG. Call the caller's on-error only when the
   original image fails too."
  [{:keys [src on-error ref] :as attrs}]
  (r/with-let [*fallback-sources (r/atom #{})]
    (let [capture! (react/use-callback
                    (fn [image]
                      ;; A server-rendered image may fail before React attaches
                      ;; on-error. Inspect it at attachment without changing DOM;
                      ;; state removes the modern sources through normal rendering.
                      (when (and image (sources/should-use-modern-formats? src)
                                 (.-complete image) (zero? (.-naturalWidth image))
                                 (modern-source-selected? image src))
                        (swap! *fallback-sources conj src))
                      (cond (fn? ref) (ref image)
                            ref (set! (.-current ^js ref) image))
                      js/undefined)
                    #js [src ref])]
     (if (sources/should-use-modern-formats? src)
      (let [avif-src (sources/replace-extension src "avif")
            webp-src (sources/replace-extension src "webp")
            ;; Keep failures by original path so a new src tries modern formats anew.
            fallback? (contains? @*fallback-sources src)
            retry! (fn [event]
                     (let [image (.-currentTarget event)]
                       ;; Safari can select AVIF in Lockdown Mode even though
                       ;; its decoder rejects it. <picture> does not retry itself.
                       (if (and (not fallback?) (modern-source-selected? image src))
                         (swap! *fallback-sources conj src)
                         (when on-error (on-error event)))))]
        [:picture
         (when-not fallback?
           [:source {:key avif-src :srcSet avif-src :type "image/avif"}])
         (when-not fallback?
           [:source {:key webp-src :srcSet webp-src :type "image/webp"}])
         [:img (assoc attrs :on-error retry! :ref capture!)]])
      ;; No modern format available, just use img directly.
      [:img attrs]))))

(defc <img>
  "Smart img component that automatically uses modern formats when available.
   Alias for picture component for drop-in replacement."
  [attrs]
  [<picture> attrs])

(defc <media-as-bg>
  "Generate picture element optimized for use as background media.
   Adds common background styling attributes."
  [{:keys [src alt class] :as attrs}]
  (let [combined-attrs (merge attrs
                              {:class (str "media media-as-bg " (or class ""))})]
    [<picture> combined-attrs]))

;; For backward compatibility - export main functions
(def ^:export responsiveImage <picture>)
