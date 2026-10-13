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
          capture! (react/use-memo
                    #(react/compose-refs
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
                        nil)
                      ref)
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

(defn- document-owner [^js image src]
  ;; The document adapter owns only the exact initial hero, through its terminal
  ;; phase. Reading its native identity is interop, not a second readiness store.
  (when image
    (let [hero (.closest image ".progressive-image")
          ^js root (some-> hero (.closest "#app"))]
      (when (and root (identical? hero (.-__progressiveHero root))
                 (= "native" (.getAttribute root "data-document-image-owner"))
                 (= src (.getAttribute root "data-document-image-src"))
                 (= src (.getAttribute image "src")))
        root))))

(defn- same-preview-candidate? [image preview-image preview sizes]
  (when (and image preview (exists? js/window))
    (let [selected (.-currentSrc image)
          candidates (set (map #(.-href (js/URL. % (.-baseURI image)))
                               ((juxt :avif :webp) preview)))]
      (if (seq selected)
        (or (contains? candidates selected)
            (and preview-image (= selected (.-currentSrc preview-image))))
        (and (= sizes "100vw")
             (<= (* (.-innerWidth js/window) (or (.-devicePixelRatio js/window) 1))
                 (:width preview)))))))

(defc ^:private <preview-layer>
  ;; A picture with display:contents has no painted native motion root. This
  ;; span lets the established presence lifecycle own the 100ms opacity exit.
  {:features [:exit]}
  [attrs]
  [:span.progressive-image__preview-layer
   [<source-picture> attrs]])

(defc ^:private <progressive-layers>
  {:features [[:presence (fn [_ phase _ _]
                          (not (#{:single :decoded} phase)))]]}
  [attrs phase preview-attrs final-attrs]
  [:span.progressive-image attrs
   (when-not (#{:single :decoded :upgrading} phase)
     ^{:key :preview} [<preview-layer> preview-attrs])
   ^{:key :final} [<source-picture> final-attrs]])

(defc <picture>
  "Render responsive modern sources with original recovery. Different candidates
   overlap for 100ms after final decode; matching candidates use a single layer.
   The initial document owns its upgrade through hydration, without replay."
  [{:keys [src class style id progressive? on-error on-load ref on-transition-end on-transition-cancel] :as attrs} :- attrs-schema]
  (r/with-let [*image-phase (r/atom {:src src, :phase nil})]
    (let [*final-image (react/use-ref nil)
          *preview-image (react/use-ref nil)
          *active? (react/use-ref true)
          preview (sources/preview src)
          progressive?' (and preview (if (contains? attrs :progressive?)
                                      progressive? (sources/progressive? src)))
          sizes (or (:sizes attrs) "100vw")
          phase (when (= src (:src @*image-phase)) (:phase @*image-phase))
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
                      (when (and progressive?' (not (document-owner image src))
                                 (same-preview-candidate? image (.-current *preview-image) preview sizes))
                        (reset! *image-phase {:src src, :phase :single})))
                    [src progressive?' sizes (:width preview) (:avif preview) (:webp preview)])
          decoded! (react/use-callback
                     (fn [image upgrade?]
                       ;; No fetch or application timer: native decode readiness
                       ;; starts declarative opacity/presence on this mounted source.
                       (when (and image (not (document-owner image src))
                                  (.-complete image) (pos? (.-naturalWidth image)))
                         (let [selected (.-currentSrc image)
                               ready! (fn []
                                        (when (and (.-current *active?)
                                                   (identical? image (.-current *final-image))
                                                   (= src (.getAttribute image "src"))
                                                   (= selected (.-currentSrc image))
                                                   (not (#{:single :decoded :failed} (:phase @*image-phase)))
                                                   (not (and (= :upgrading (:phase @*image-phase))
                                                             (= selected (:selected @*image-phase)))))
                                          (let [preview-image (.-current *preview-image)
                                                upgrading? (= :upgrading (:phase @*image-phase))
                                                settled? (and upgrading?
                                                              (>= (js/parseFloat (.-opacity (js/getComputedStyle image))) 0.999))
                                                overlap? (and upgrade? preview-image
                                                              (pos? (.-naturalWidth preview-image))
                                                              (not (.. js/window
                                                                       (matchMedia "(prefers-reduced-motion: reduce)")
                                                                       -matches)))]
                                            (reset! *image-phase
                                                    {:src src
                                                     :phase (cond
                                                              upgrading? (if settled? :decoded :upgrading)
                                                              overlap? :upgrading
                                                              :else :decoded)
                                                     :selected selected}))))]
                           (if (.-decode image)
                             (-> (.decode image) (.then ready!) (.catch (fn [_] nil)))
                             (ready!)))))
                     [src])
          capture! (react/use-memo
                     #(react/compose-refs
                       (fn [image]
                       (set! (.-current *final-image) image)
                       (if (document-owner image src)
                         (reset! *image-phase {:src src, :phase :native})
                         (do
                           (single! image)
                           (when (and image (not (.-complete image))
                                      (or (not= src (:src @*image-phase))
                                          (nil? (:phase @*image-phase))))
                             (reset! *image-phase {:src src, :phase :waiting}))
                           (decoded! image false)))
                         nil)
                       ref)
                     [src ref single! decoded!])
          loaded! (react/use-callback
                    (fn [event]
                      (single! (.-currentTarget event))
                      (decoded! (.-currentTarget event) true)
                      (when on-load (on-load event)))
                    [src on-load single! decoded!])
          upgraded! (react/use-callback
                      (fn [event]
                        (when (and (= "opacity" (.-propertyName event))
                                   (identical? (.-currentTarget event) (.-current *final-image))
                                   (= src (:src @*image-phase))
                                   (= :upgrading (:phase @*image-phase))
                                   (= (:selected @*image-phase)
                                      (.-currentSrc (.-currentTarget event))))
                          (reset! *image-phase {:src src, :phase :decoded}))
                        (when-let [handler (case (.-type event)
                                             "transitionend" on-transition-end
                                             "transitioncancel" on-transition-cancel
                                             nil)]
                          (handler event)))
                      [src on-transition-end on-transition-cancel])]
      (react/use-effect
        (fn []
          (set! (.-current *active?) true)
          (when-not (= src (:src @*image-phase))
            (reset! *image-phase {:src src, :phase nil}))
          (if-let [root (document-owner (.-current *final-image) src)]
            (let [adopt! (fn adopt! [_]
                           (when (document-owner (.-current *final-image) src)
                             (when-let [terminal (get {"ready" :decoded
                                                      "single" :single
                                                      ;; Modern recovery is owned by source-picture.
                                                      "failed" (if (modern-source-selected?
                                                                       (.-current *final-image) src)
                                                                 :retry :failed)}
                                                     (.getAttribute root "data-document-image-phase"))]
                               (reset! *image-phase {:src src, :phase terminal})
                               (.removeEventListener root "tolgraven:document-image-ready" adopt!))))]
              (.addEventListener root "tolgraven:document-image-ready" adopt!)
              (adopt! nil)
              #(do (set! (.-current *active?) false)
                   (.removeEventListener root "tolgraven:document-image-ready" adopt!)))
            (do
              (single! (.-current *final-image))
              (decoded! (.-current *final-image) false)
              #(set! (.-current *active?) false))))
        [src single! decoded!])
      (if progressive?'
        (let [managed? (or (and phase (not= :native phase))
                           (not= src (:src @*image-phase)))
              final-attrs (-> attrs
                              (dissoc :class :style :id :progressive?)
                              (assoc :class "progressive-image__final"
                                     :data-image-final true
                                     :style {:visibility (when (= :failed phase) "hidden")}
                                     :ref capture!
                                     :on-load loaded!
                                     :on-transition-end upgraded!
                                     :on-transition-cancel upgraded!
                                     :on-error failed!
                                     :on-source-error pending!
                                     :on-attached-error failed!))
              preview-attrs {:src src
                             :alt ""
                             :aria-hidden true
                             :preview? true
                             :class "progressive-image__preview"
                             :data-image-preview true
                             :ref *preview-image
                             :loading (or (:loading attrs) "eager")
                             :fetchPriority (or (:fetchPriority attrs) "auto")}
              root-attrs {:id id
                          :class class
                          :style style
                          :data-preview-width (:width preview)
                          :data-image-sizes sizes
                          :data-image-phase (when managed? (some-> phase name))
                          :data-image-managed (when managed? true)
                          :data-image-retrying (when (#{:retry :failed} phase) true)}]
          [<progressive-layers> root-attrs phase preview-attrs final-attrs])
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
