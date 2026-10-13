(ns tolgraven.components.image
  "Helpers for serving modern image formats (WebP, AVIF) with automatic fallbacks"
  (:require
    [tolgraven.component.registry]
    [tolgraven.content.schema :as content]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.components.image.sources :as sources]
    [tolgraven.react :as react]
    [reagent.core :as r]))

(def attrs-schema
  ;; Pending avatars intentionally reserve their box without an image request.
  ;; CMS media uses a concrete source; a mounted image may temporarily omit it.
  (vector :merge content/media [:map [:?src :?string]
                               [:?on-error [:maybe fn?]]
                               [:?on-load [:maybe fn?]]
                               [:?style :?map]
                               [:?progressive? :boolean]]))

(def get-src-variants sources/get-src-variants)

(defn- modern-source-selected? [image src]
  (let [current-src (.-currentSrc image)]
    (and (seq current-src)
         (not= current-src (.-href (js/URL. src (.-baseURI image)))))))

(defc ^:private <source-picture>
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
  [{:keys [src on-error ref preview? on-attached-error on-source-error] :as attrs} :- attrs-schema]
  (r/with-let [*fallback-sources (r/atom #{})]
    (let [attrs' (merge {:decoding "async"} (sources/dimensions src)
                        (dissoc attrs :preview? :progressive? :on-attached-error :on-source-error))
          preview (when preview? (sources/preview src))
          capture! (react/use-callback
                    (fn [image]
                      ;; A server-rendered image may fail before React attaches
                      ;; on-error. Inspect it at attachment without changing DOM;
                      ;; state removes the modern sources through normal rendering.
                      (when (and image (seq src) (seq (.-currentSrc image))
                                 (.-complete image) (zero? (.-naturalWidth image)))
                        (when on-source-error (on-source-error))
                        (if (and (sources/should-use-modern-formats? src)
                                 (modern-source-selected? image src))
                          (swap! *fallback-sources conj src)
                          (when on-attached-error
                            (on-attached-error #js {:currentTarget image}))))
                      (cond (fn? ref) (ref image)
                            ref (set! (.-current ^js ref) image))
                      js/undefined)
                    [src ref on-attached-error on-source-error])]
     (if (sources/should-use-modern-formats? src)
      (let [avif-src (sources/replace-extension src "avif")
            webp-src (sources/replace-extension src "webp")
            ;; Keep failures by original path so a new src tries modern formats anew.
            fallback? (contains? @*fallback-sources src)
            retry! (fn [event]
                     (let [image (.-currentTarget event)]
                       (when on-source-error (on-source-error))
                       ;; Safari can select AVIF in Lockdown Mode even though
                       ;; its decoder rejects it. <picture> does not retry itself.
                       (if (and (not fallback?) (modern-source-selected? image src))
                         (swap! *fallback-sources conj src)
                         (when on-error (on-error event)))))]
        [:picture
         (when-not fallback?
           [:source {:key avif-src
                     :srcSet (or (:avif preview) (sources/srcset src "avif") avif-src)
                     :sizes (or (:sizes attrs') "100vw")
                     :type "image/avif"}])
         (when-not fallback?
           [:source {:key webp-src
                     :srcSet (or (:webp preview) (sources/srcset src "webp") webp-src)
                     :sizes (or (:sizes attrs') "100vw")
                     :type "image/webp"}])
         [:img (assoc attrs' :on-error retry! :ref capture!)]])
      ;; No modern format available, just use img directly.
      [:img attrs']))))

(defn- same-preview-candidate? [image preview sizes]
  ;; The browser remains authoritative when its selection is exposed. Pending
  ;; currentSrc can be empty; the declared 100vw slot still determines the small
  ;; bucket without keeping a second viewport/application store.
  (when (and image preview (exists? js/window))
    (let [selected (.-currentSrc image)
          candidates (set (map #(.-href (js/URL. % (.-baseURI image)))
                               ((juxt :avif :webp) preview)))]
      (if (seq selected)
        (contains? candidates selected)
        (and (= sizes "100vw")
             (<= (* (.-innerWidth js/window) (or (.-devicePixelRatio js/window) 1))
                 (:width preview)))))))

(defc <picture>
  "Render responsive modern sources with original-format recovery. Catalog-owned
   progressive images paint a decorative blurred preview while the final decodes.
   Native SSR starts both requests; the committed lifecycle removes the preview
   as soon as decoded final pixels can stand alone. Override with :progressive?."
  [{:keys [src class style id progressive? on-error on-load ref] :as attrs} :- attrs-schema]
  (r/with-let [*image-phase (r/atom {:src src, :phase nil})]
    (let [*final-image (react/use-ref nil)
          *active? (react/use-ref true)
          preview (sources/preview src)
          progressive?' (and preview (if (contains? attrs :progressive?)
                                      progressive? (sources/progressive? src)))
          sizes (or (:sizes attrs) "100vw")
          failed! (react/use-callback
                    (fn [event]
                      (when-not (= {:src src, :phase :failed} @*image-phase)
                        (reset! *image-phase {:src src, :phase :failed})
                        (when on-error (on-error event))))
                    [src on-error])
          pending! (react/use-callback
                     #(when-not (= {:src src, :phase :failed} @*image-phase)
                        (reset! *image-phase {:src src, :phase :retry}))
                     [src])
          single! (react/use-callback
                    (fn [image]
                      (when (and progressive?' (same-preview-candidate? image preview sizes))
                        (reset! *image-phase {:src src, :phase :single})))
                    [src progressive?' sizes (:width preview) (:avif preview) (:webp preview)])
          decoded! (react/use-callback
                     (fn [image]
                       ;; Native decode belongs to this mounted image adapter.
                       ;; Ignore completions after source changes/unmount; pixels
                       ;; must be usable before removing their low-resolution layer.
                       (when (and image (.-complete image) (pos? (.-naturalWidth image)))
                         (let [selected (.-currentSrc image)
                               ready! #(when (and (.-current *active?)
                                                  (identical? image (.-current *final-image))
                                                  (= src (.getAttribute image "src"))
                                                  (= selected (.-currentSrc image)))
                                         (reset! *image-phase {:src src, :phase :decoded}))]
                           (if (.-decode image)
                             (-> (.decode image) (.then ready!) (.catch (fn [_] nil)))
                             (ready!)))))
                     [src])
          capture! (react/use-callback
                     (fn [image]
                       (set! (.-current *final-image) image)
                       (single! image)
                       (decoded! image)
                       (cond (fn? ref) (ref image)
                             ref (set! (.-current ^js ref) image))
                       js/undefined)
                     [src ref single! decoded!])
          loaded! (react/use-callback
                    (fn [event]
                      (single! (.-currentTarget event))
                      (decoded! (.-currentTarget event))
                      (when on-load (on-load event)))
                    [src on-load single! decoded!])]
      (react/use-effect
        (fn []
          (set! (.-current *active?) true)
          (when-not (= src (:src @*image-phase))
            (reset! *image-phase {:src src, :phase nil}))
          (single! (.-current *final-image))
          (decoded! (.-current *final-image))
          #(set! (.-current *active?) false))
        [src single! decoded!])
      (if progressive?'
        (let [phase (when (= src (:src @*image-phase)) (:phase @*image-phase))
              final-attrs (-> attrs
                              (dissoc :class :style :id :progressive?)
                              (assoc :class "progressive-image__final"
                                     :data-image-final true
                                     :style {:visibility (when (= :failed phase) "hidden")}
                                     :ref capture!
                                     :on-load loaded!
                                     :on-error failed!
                                     :on-source-error pending!
                                     :on-attached-error failed!))]
          [:span.progressive-image
           {:id id
            :class class
            :style style
            :data-preview-width (:width preview)
            :data-image-sizes sizes
            :data-image-retrying (when (#{:retry :failed} phase) true)}
           (when-not (#{:single :decoded} phase)
             [<source-picture> {:src src
                                :alt ""
                                :aria-hidden true
                                :preview? true
                                :class "progressive-image__preview"
                                :data-image-preview true
                                :loading (or (:loading attrs) "eager")
                                :fetchPriority (or (:fetchPriority attrs) "auto")}])
           [<source-picture> final-attrs]])
        [<source-picture> attrs]))))

(defc <img>
  "Smart img component that automatically uses modern formats when available.
   Alias for picture component for drop-in replacement."
  [attrs :- attrs-schema]
  [<picture> attrs])

(defc <media-as-bg>
  "Generate picture element optimized for use as background media.
   Adds common background styling attributes."
  [{:keys [src alt class] :as attrs} :- attrs-schema]
  (let [combined-attrs (merge {:loading "lazy"} attrs
                              {:alt (or alt "")
                               :class (str "media media-as-bg " (or class ""))})]
    [<picture> combined-attrs]))

;; For backward compatibility - export main functions
(def ^:export responsiveImage <picture>)
