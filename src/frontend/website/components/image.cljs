(ns website.components.image
  "Helpers for serving modern image formats (WebP, AVIF) with automatic fallbacks"
  (:require
    [website.component.registry]
    [website.component]
    [website.component.restore :as restore]
    [website.component.visibility :as visibility]
    [clojure.string :as string]
    [website.content.schema :as content]
    [website.content.fields :as fields]
    [website.media.schema :as media-schema]
    [website.macros :refer-macros [defc]]
    [website.components.image.sources :as sources]
    [website.react :as r]
    [website.listener :as listener]))

(def attrs-schema
  ;; Pending avatars intentionally reserve their box without an image request.
  ;; CMS media uses a concrete source; a mounted image may temporarily omit it.
  (vector :merge content/media [:map [:?src :?string]
                               [:?on-error [:maybe fn?]]
                               [:?on-load [:maybe fn?]]
                               [:?on-visible-ready [:maybe fn?]]
                               [:?style :?map]
                               [:?progressive? :boolean]
                               [:?asset [:maybe media-schema/asset]]]))

(def visible-ready-schema
  [:map [:src :string] [:selected-src :string]])

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
  [{:keys [src asset on-error ref preview? on-attached-error on-source-error] :as attrs} :- attrs-schema]
  (r/with-let [*fallback-sources (r/atom #{})]
    (let [attrs' (merge {:decoding "async"} (sources/dimensions src asset)
                        (dissoc attrs :asset :renditions :placeholder :preview? :progressive? :on-attached-error :on-source-error :on-visible-ready))
          preview (when preview? (sources/preview src asset))
          capture! (r/use-memo
                    #(r/compose-refs
                      (fn [image]
                        ;; A server-rendered image may fail before React attaches
                        ;; on-error. Inspect it at attachment without changing DOM;
                        ;; state removes the modern sources through normal rendering.
                        (when (and image (seq src) (seq (.-currentSrc image))
                                   (.-complete image) (zero? (.-naturalWidth image)))
                          (when on-source-error (on-source-error))
                          (if (and (sources/should-use-modern-formats? src asset)
                                   (modern-source-selected? image src))
                            (swap! *fallback-sources conj src)
                            (when on-attached-error
                              (on-attached-error #js {:currentTarget image}))))
                        nil)
                      ref)
                    [src asset ref on-attached-error on-source-error])]
     (if (sources/should-use-modern-formats? src asset)
      (let [{avif-src :avif, webp-src :webp} (sources/get-src-variants src asset)
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
                     :srcSet (or (:avif preview) (sources/srcset src "avif" asset) avif-src)
                     :sizes (or (:sizes attrs') "100vw")
                     :type "image/avif"}])
         (when-not fallback?
           [:source {:key webp-src
                     :srcSet (or (:webp preview) (sources/srcset src "webp" asset) webp-src)
                     :sizes (or (:sizes attrs') "100vw")
                     :type "image/webp"}])
         [:img (assoc attrs' :on-error retry! :ref capture!)]])
      ;; No modern format available, just use img directly.
      [:img attrs']))))

(defn- document-owner [^js image src]
  ;; Initial images share a document-owned collection, keyed by exact native
  ;; wrapper identity. No later SPA instance inherits an old staging owner.
  (when image
    (let [wrapper (.closest image ".progressive-image, img[data-image-placeholder]")
          ^js root (some-> wrapper (.closest "#app"))
          ^js owners (some-> root (aget "__progressiveImages"))
          ^js owner (when owners (.get owners wrapper))]
      (when (and owner ((aget owner "current"))
                 (= src (aget owner "src"))
                 (= src (.getAttribute image "src")))
        owner))))

(defn- noticeable-preview? [image]
  ;; Use actual viewport exposure and the real entrance transform, including CSS
  ;; easing and paused clocks. Early finals have no minimum preview duration.
  (when image
    (let [wrapper (.closest image ".progressive-image")
          rect (.getBoundingClientRect wrapper)]
      (when (and (pos? (.-width rect)) (pos? (.-height rect))
                 (pos? (.-bottom rect)) (pos? (.-right rect))
                 (< (.-top rect) (.-innerHeight js/window))
                 (< (.-left rect) (.-innerWidth js/window)))
        (loop [node wrapper]
          (if (or (nil? node) (= "app" (.-id node)))
            true
            (let [style (js/getComputedStyle node)
                  transform (.-transform style)
                  scale-x (if (= transform "none") 1
                            (.-a (js/DOMMatrixReadOnly. transform)))]
              (and (not= "hidden" (.-visibility style))
                   (not= "none" (.-display style))
                   (> (js/parseFloat (.-opacity style)) 0)
                   (> (js/Math.abs scale-x) (/ 2 3))
                   (recur (.-parentElement node))))))))))

(defn- in-viewport? [image]
  (let [rect (.getBoundingClientRect image)]
    (and (pos? (.-width rect)) (pos? (.-height rect))
         (pos? (.-bottom rect)) (pos? (.-right rect))
         (< (.-top rect) (.-innerHeight js/window))
         (< (.-left rect) (.-innerWidth js/window)))))

(defn- painted-image? [image]
  (and (= "visible" (.-visibilityState js/document))
       (in-viewport? image)
       (loop [node image]
         (if-not node
           true
           (let [style (js/getComputedStyle node)]
             (and (not= "hidden" (.-visibility style))
                  (not= "none" (.-display style))
                  (pos? (js/parseFloat (.-opacity style)))
                  (recur (.-parentElement node))))))))

(defn- use-visible-ready! [*image src state callback]
  (let [*notification (r/use-ref {:src src})]
    (r/use-effect
      (fn []
        (when-not (= src (:src (.-current *notification)))
          (set! (.-current *notification) {:src src}))
        (when (and callback (exists? js/window)
                   (= src (:src state)) (= :decoded (:phase state))
                   (seq (:selected state)) (not (:notified? (.-current *notification))))
          (let [image (.-current *image)
                selected (:selected state)
                *active? (volatile! true)
                *frame (volatile! nil)
                *visibility (volatile! nil)
                *observer (volatile! nil)
                *stop-listeners (volatile! nil)]
            ;; The normal visibility root of a modern picture is display:contents.
            ;; Reuse its observer setup on the committed native IMG, then wait two
            ;; paint frames after final decode/fade. Only opt-in callbacks acquire
            ;; this adapter; offscreen images wait on the observer without polling.
            (letfn [(cleanup! []
                      (vreset! *active? false)
                      (when @*frame (js/cancelAnimationFrame @*frame))
                      (when @*visibility ((:stop! @*visibility)))
                      (when @*observer (.disconnect @*observer))
                      (when @*stop-listeners (@*stop-listeners)))
                    (current? []
                      (and @*active? image
                           (identical? image (.-current *image))
                           (.contains js/document image)
                           (= src (.getAttribute image "src"))
                           (= selected (.-currentSrc image))))
                    (paint! []
                      (vreset! *frame nil)
                      (when (current?)
                        (when (and (.-complete image) (pos? (.-naturalWidth image)))
                          (if (painted-image? image)
                            (do
                              (cleanup!)
                              (set! (.-current *notification) {:src src, :notified? true})
                              (callback {:src src, :selected-src selected}))
                            ;; Hidden ancestors wait for their next native change
                            ;; or motion completion; never keep requesting frames.
                            nil))))
                    (schedule! []
                      (when (and (current?) (nil? @*frame))
                        (vreset! *frame
                                 (js/requestAnimationFrame
                                   (fn []
                                     (vreset! *frame (js/requestAnimationFrame paint!)))))))]
              (when image
                (let [ancestors (take-while some? (iterate #(.-parentElement %) image))
                      observer (js/MutationObserver. (fn [_ _] (schedule!)))]
                  (vreset! *observer observer)
                  (doseq [ancestor ancestors]
                    (.observe observer ancestor
                              #js {:attributes true
                                   :attributeFilter #js ["class" "style" "hidden" "data-document-pending"]}))
                  (vreset! *stop-listeners
                           (listener/listen!
                             :image-visible-ready
                             (concat
                               [{:target js/document
                                 :event "visibilitychange"
                                 :handler (fn [_] (schedule!))}]
                               (for [ancestor ancestors
                                     event ["transitionend" "transitioncancel" "animationend" "animationcancel"]]
                                 {:target ancestor
                                  :event event
                                  :handler (fn [event]
                                             (when (identical? ancestor (.-target event))
                                               (schedule!)))})))))
                (vreset! *visibility (visibility/setup {:callback schedule!
                                                        :once? false
                                                        :threshold 0}))
                ((:mount! @*visibility) image))
              cleanup!))))
      [src (:phase state) (:selected state) callback])))

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
  ;; span lets the established presence lifecycle own the 150ms opacity exit.
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

(defc ^:private <picture-body>
  "Render responsive modern sources with original recovery. Different candidates
   overlap for 150ms after final decode; matching candidates use a single layer.
   The initial document owns its upgrade through hydration, without replay."
  [{:keys [asset class style id progressive? on-error on-load ref on-transition-end on-transition-cancel on-visible-ready] :as attrs} :- attrs-schema]
  (let [src (or (:src asset) (:src attrs))
        attrs' (assoc attrs :src src)]
   (r/with-let [*image-phase (r/atom {:src src, :phase nil})]
    (let [*final-image (r/use-ref nil)
          *preview-image (r/use-ref nil)
          *active? (r/use-ref true)
          image-key (string/replace (r/use-id) #"[^A-Za-z0-9_-]" "_")
          initial-hydration? (r/use-memo restore/initial-hydration? [])
          direct? (restore/direct-images?)
          preview (sources/preview src asset)
          progressive?' (and preview (if (contains? attrs' :progressive?)
                                      progressive? (sources/progressive? src asset)))
          background (sources/placeholder-background src asset)
          dimensions (sources/dimensions src asset)
          placeholder? (and background (>= (max (or (:width dimensions) 0)
                                               (or (:height dimensions) 0)) 960))
          sizes (or (:sizes attrs') "100vw")
          phase (when (= src (:src @*image-phase)) (:phase @*image-phase))
          phase' (cond
                   (not progressive?') (if (= :decoded phase) :decoded :single)
                   (and direct? (or (not initial-hydration?) (restore/local-document?) phase))
                   (if (= :decoded phase) :decoded :single)
                   :else phase)
          failed! (r/use-callback
                    (fn [event]
                      (when-not (= {:src src, :phase :failed} @*image-phase)
                        (reset! *image-phase {:src src, :phase :failed})
                        (when on-error (on-error event))))
                    [src on-error])
          pending! (r/use-callback
                     #(when-not (= {:src src, :phase :failed} @*image-phase)
                        (reset! *image-phase {:src src, :phase :retry}))
                     [src])
          single! (r/use-callback
                    (fn [image]
                      (when (and progressive?' (not (document-owner image src))
                                 (or direct? (same-preview-candidate? image (.-current *preview-image) preview sizes)))
                        (reset! *image-phase {:src src, :phase :single})))
                    [src direct? progressive?' sizes (:width preview) (:avif preview) (:webp preview)])
          decoded! (r/use-callback
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
                                                   (not= :failed (:phase @*image-phase))
                                                   (not (and (= :decoded (:phase @*image-phase))
                                                             (= selected (:selected @*image-phase))))
                                                   (not (and (= :upgrading (:phase @*image-phase))
                                                             (= selected (:selected @*image-phase)))))
                                          (let [preview-image (.-current *preview-image)
                                                upgrading? (= :upgrading (:phase @*image-phase))
                                                settled? (and upgrading?
                                                              (>= (js/parseFloat (.-opacity (js/getComputedStyle image))) 0.999))
                                                overlap? (and upgrade? (not direct?) (not= :single (:phase @*image-phase)) preview-image
                                                              (noticeable-preview? image)
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
                     [src direct?])
          capture! (r/use-memo
                     #(r/compose-refs
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
          loaded! (r/use-callback
                    (fn [event]
                      (single! (.-currentTarget event))
                      (decoded! (.-currentTarget event) true)
                      (when on-load (on-load event)))
                    [src on-load single! decoded!])
          upgraded! (r/use-callback
                      (fn [event]
                        (when (and (= "opacity" (.-propertyName event))
                                   (identical? (.-currentTarget event) (.-current *final-image))
                                   (= src (:src @*image-phase))
                                   (= :upgrading (:phase @*image-phase))
                                   (= (:selected @*image-phase)
                                      (.-currentSrc (.-currentTarget event))))
                          (reset! *image-phase {:src src
                                               :phase :decoded
                                               :selected (.-currentSrc (.-currentTarget event))}))
                        (when-let [handler (case (.-type event)
                                             "transitionend" on-transition-end
                                             "transitioncancel" on-transition-cancel
                                             nil)]
                          (handler event)))
                      [src on-transition-end on-transition-cancel])]
      (r/use-effect
        (fn []
          (set! (.-current *active?) true)
          (when-not (= src (:src @*image-phase))
            (reset! *image-phase {:src src, :phase nil}))
          (if-let [owner (document-owner (.-current *final-image) src)]
            (if (#{:single :decoded :retry :failed} phase)
              (do
                ;; A final-only paired wrapper was managed in SSR already; its
                ;; exact native owner cannot infer this commit from that marker.
                ((aget owner "handoff"))
                (decoded! (.-current *final-image) false)
                #(set! (.-current *active?) false))
              (let [root (aget owner "root")
                    adopt! (fn [_]
                           (when (identical? owner (document-owner (.-current *final-image) src))
                             (when-let [terminal (get {"ready" :decoded
                                                      "single" (if (aget owner "finalReady") :decoded :single)
                                                      "failed" (if (modern-source-selected?
                                                                       (.-current *final-image) src)
                                                                 :retry :failed)}
                                                     (aget owner "phase"))]
                               (reset! *image-phase {:src src, :phase terminal
                                                      :selected (.-currentSrc (.-current *final-image))})
                               terminal)))
                    stop-listeners! (listener/listen! :progressive-image
                                    [{:target root
                                      :event "tolgraven:document-image-ready"
                                      :handler adopt!}])]
              (when (adopt! nil) (stop-listeners!))
              #(do (set! (.-current *active?) false)
                   (stop-listeners!))))
            (do
              (single! (.-current *final-image))
              (decoded! (.-current *final-image) false)
              #(set! (.-current *active?) false))))
        [src single! decoded! direct? phase])
      (use-visible-ready! *final-image src @*image-phase on-visible-ready)
      (cond
        progressive?'
        (let [managed? (or (and phase' (not= :native phase'))
                           (not= src (:src @*image-phase)))
              final-attrs (-> attrs'
                              (dissoc :class :style :id :progressive? :on-visible-ready)
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
                             :asset asset
                             :alt ""
                             :aria-hidden true
                             :preview? true
                             :class "progressive-image__preview"
                             :data-image-preview true
                             :ref *preview-image
                             :loading "lazy"
                             :fetchPriority (or (:fetchPriority attrs') "auto")}
              root-attrs {:id id
                          :class class
                          :style (merge {"--image-final-opacity" (str "var(--pi-" image-key "-final, 0)")
                                         "--image-preview-opacity" (str "var(--pi-" image-key "-preview, 1)")
                                         "--image-preview-blur" (str "var(--pi-" image-key "-blur, 4rem)")
                                         "--image-motion" (str "var(--pi-" image-key "-motion, none)")
                                         "--image-placeholder" (str "var(--pi-" image-key "-placeholder, "
                                                                      (or background "none") ")")}
                                        style)
                          :data-image-key image-key
                          :data-image-placeholder (when background true)
                          :data-preview-width (:width preview)
                          :data-image-sizes sizes
                          :data-image-phase (when managed? (some-> phase' name))
                          :data-image-managed (when managed? true)
                          :data-image-retrying (when (#{:retry :failed} phase') true)}]
          [<progressive-layers> root-attrs phase' preview-attrs final-attrs])

        placeholder?
        [<source-picture>
         (assoc (dissoc attrs' :on-visible-ready)
                :style (merge {:background-image (if (= :decoded phase) "none"
                                                    (str "var(--pi-" image-key "-placeholder, " background ")"))}
                              style)
                :data-image-key image-key
                :data-image-placeholder true
                :data-image-phase (name phase')
                :data-image-managed true
                :data-image-final true
                :ref capture!
                :on-load loaded!
                :on-error failed!
                :on-source-error pending!
                :on-attached-error failed!)]

        on-visible-ready
        [<source-picture> (assoc (dissoc attrs' :on-visible-ready)
                                :ref capture!
                                :on-load loaded!
                                :on-error failed!
                                :on-source-error pending!
                                :on-attached-error failed!)]

        :else [<source-picture> (dissoc attrs' :on-visible-ready)])))))

(defc <picture>
  "Responsive picture with an inline replacement control for editable CMS media."
  [attrs :- attrs-schema]
  (let [path (fields/path attrs [:src])
        draft (when (and path (fields/editing? attrs))
                @(r/subscribe [:get :state :content-editor :fields (pr-str path)]))
        attrs' (if (and draft (not= (:expected draft) (:value draft)))
                 (-> attrs (dissoc :asset :renditions :placeholder) (assoc :src (:value draft))) attrs)]
    [fields/<image> attrs [<picture-body> (dissoc attrs' :cms-src)]]))

(defc <media-as-bg>
  "Generate picture element optimized for use as background media.
   Adds common background styling attributes."
  [{:keys [src alt class] :as attrs} :- attrs-schema]
  (let [combined-attrs (with-meta (merge {:loading "lazy"} attrs
                              {:alt (or alt "")
                               :class (str "media media-as-bg " (or class ""))}) (meta attrs))]
    [<picture> combined-attrs]))
