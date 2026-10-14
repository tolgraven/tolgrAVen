(ns bootstrap.entrance
  "Native image/decode staging before application code, with document-only writes."
  (:require [bootstrap.skeleton :as skeleton]
            [bootstrap.destination :as destination]))

(defn- direct? [] (.hasAttribute (.-documentElement js/document) "data-image-direct"))
(defn- reduced-motion? [] (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))
(defn- usable? [^js image] (and image (.-complete image) (> (.-naturalWidth image) 0)))
(defn- terminal? [phase] (.includes #js ["ready" "single" "failed"] phase))

(defn- visible? [^js root ^js image]
  (and image (.contains root image)
       (loop [node image]
         (if (or (nil? node) (identical? node root))
           true
           (let [style (js/getComputedStyle node)]
             (and (not (identical? "none" (.-display style)))
                  (not (identical? "hidden" (.-visibility style)))
                  (not (identical? 0 (js/Number (.-opacity style))))
                  (recur (.-parentElement node))))))))

(defn- noticeable? [root ^js image]
  (and (visible? root image)
       (let [rect (when (.-getBoundingClientRect image) (.getBoundingClientRect image))]
         (or (nil? rect)
             (and (> (.-width rect) 0) (> (.-height rect) 0)
                  (> (.-bottom rect) 0) (> (.-right rect) 0)
                  (< (.-top rect) js/innerHeight) (< (.-left rect) js/innerWidth))))
       (loop [node image]
         (if (or (nil? node) (identical? node root))
           true
           (let [transform (.-transform (js/getComputedStyle node))]
             (and (or (not transform) (identical? "none" transform)
                      (> (js/Math.abs (.-a (js/DOMMatrixReadOnly. transform))) (/ 2 3)))
                  (recur (.-parentElement node))))))))

(defn- acquire! [^js root ^js owners ^js entries ^js gate reveal! ^js wrapper]
  (let [preview (.querySelector wrapper "[data-image-preview]")
        final (if (.matches wrapper "img") wrapper
                  (.querySelector wrapper "[data-image-final]"))
        key (.getAttribute wrapper "data-image-key")]
    (when (and final key)
      (let [src (.getAttribute final "src")
            prefix (.concat "--pi-" key "-")
            bindings #js []
            failed (js/Set.)
            preview-sources (or (some-> preview (.closest "picture") (.querySelectorAll "source")) #js [])
            candidates (.map (js/Array.from preview-sources)
                             (fn [^js source]
                               (.-href (js/URL. (.getAttribute source "srcset") (.-baseURI final)))))
            ^js owner #js {:root root
                          :src src
                          :phase "waiting"
                          :staging true
                          :disposed false
                          :painted false
                          :previewReady false
                          :finalReady false
                          :current nil
                          :previewPainted nil
                          :gateFinished nil
                          :handoff nil}]
        (letfn [(owned? [] (and (not (.-disposed owner)) (.contains root wrapper)
                                (identical? src (.getAttribute final "src"))))
                (current? [] (and (.-staging owner) (owned?)
                                  (or (not preview)
                                      (not (.hasAttribute wrapper "data-image-managed")))))
                (visible-preview? []
                  ;; Ignore the page gate's inherited visibility here: this
                  ;; decoded preview may open it, unlike a hidden Back/same layer.
                  (and preview (.contains root preview)
                       (not (identical? "none" (.-display (js/getComputedStyle preview))))
                       (not (identical? "none" (.-display (js/getComputedStyle
                                                (or (.closest preview ".progressive-image__preview-layer") preview)))))))
                (same? []
                  (or (not preview) (and (> (.-length (.-currentSrc final)) 0)
                           (or (and preview (identical? (.-currentSrc final) (.-currentSrc preview)))
                               (.includes candidates (.-currentSrc final))))
                      (and (not (> (.-length (.-currentSrc final)) 0))
                           (identical? "100vw" (.getAttribute wrapper "data-image-sizes"))
                           (<= (* js/innerWidth js/devicePixelRatio)
                               (js/Number (.getAttribute wrapper "data-preview-width"))))))
                (property! [name value] (.setProperty (.-style root) (.concat prefix name) value))
                (stage! [phase]
                  (when (current?)
                    (set! (.-phase owner) phase)
                    (property! "final" (if (.includes #js ["waiting" "failed"] phase) "0" "1"))
                    (property! "preview" (if (.includes #js ["waiting" "failed"] phase) "1" "0"))
                    (property! "blur" (if (.includes #js ["upgrading" "ready" "single"] phase) "0rem" "4rem"))
                    (property! "motion" (if (identical? "upgrading" phase) "opacity 150ms linear" "none"))
                    (when (terminal? phase)
                      (js/clearTimeout (.-upgradeDeadline owner))
                      (js/cancelAnimationFrame (.-frame owner))
                      (.dispatchEvent root (js/Event. "tolgraven:document-image-ready")))))
                (release! []
                  (when (.-staging owner)
                    (set! (.-staging owner) false)
                    (js/clearTimeout (.-upgradeDeadline owner))
                    (js/cancelAnimationFrame (.-frame owner))
                    (.forEach #js ["final" "preview" "blur" "motion" "placeholder"]
                              #(.removeProperty (.-style root) (str prefix %)))
                    (when (identical? (.get owners wrapper) owner) (.delete owners wrapper))))
                (handoff! []
                  ;; Paired SSR already has a managed marker. Its component
                  ;; acknowledges adoption after committing the terminal phase.
                  (release!)
                  (when (or (not (identical? wrapper (.-hero gate)))
                            (not (.-pending gate)) (.-disposed gate))
                    (cleanup!)))
                (cleanup! []
                  (when-not (.-disposed owner)
                    (release!)
                    (set! (.-disposed owner) true)
                    (.forEach bindings (fn [binding]
                                         (.removeEventListener (aget binding 0) "load" (aget binding 1))
                                         (.removeEventListener (aget binding 0) "error" (aget binding 2))))
                    (.disconnect (.-observer owner))
                    (.removeEventListener js/window "pageshow" restored!)
                    (.removeEventListener js/window "pagehide" departed!)
                    (.removeEventListener final "transitionend" transitioned!)
                    (.removeEventListener final "transitioncancel" transitioned!)))
                (finish! [selected]
                  (when (and (current?) (identical? "upgrading" (.-phase owner))
                             (identical? (.-currentSrc final) selected))
                    (stage! "ready")))
                (transitioned! [^js event]
                  (when (and (identical? (.-target event) final) (identical? "opacity" (.-propertyName event)))
                    (finish! (.-selectedUpgrade owner))))
                (painted! []
                  (set! (.-painted owner)
                        (and (owned?) (.-previewReady owner) (not (.-finalReady owner))
                             (visible? root preview))))
                (gate-ready! [image]
                  (when (and (identical? wrapper (.-hero gate)) (owned?)
                             (or (not (identical? image preview))
                                 (and (not (direct?)) (not (same?)) (visible-preview?))))
                    (reveal!)))
                (await-upgrade! []
                  (when (current?)
                    (let [durations (.map (.split (.-transitionDuration (js/getComputedStyle final)) ",")
                                          (fn [value] (* (js/parseFloat value)
                                                         (if (.endsWith (.trim value) "ms") 1 1000))))
                          animations (if (.-getAnimations final) (.getAnimations final) #js [])]
                      (set! (.-upgradeDeadline owner)
                            (js/setTimeout #(finish! (.-selectedUpgrade owner))
                                           (+ (.apply js/Math.max nil durations) 50)))
                      (when (> (.-length animations) 0)
                        (.then (js/Promise.allSettled (.map animations (fn [^js animation] (.-finished animation))))
                               #(finish! (.-selectedUpgrade owner)))))))
                (ready! [image]
                  (when (and (owned?) (.contains root image))
                    (if (identical? image final)
                      (when-not (and (.-finalReady owner) (identical? (.-selectedReady owner) (.-currentSrc final)))
                        (set! (.-finalReady owner) true)
                        (when (current?) (property! "placeholder" "none"))
                        (set! (.-selectedReady owner) (.-currentSrc final))
                        (when (current?)
                          (cond
                            (or (direct?) (same?)) (stage! "single")
                            (identical? "upgrading" (.-phase owner))
                            (do (set! (.-selectedUpgrade owner) (.-selectedReady owner))
                                (when (>= (js/Number (.-opacity (js/getComputedStyle final))) 1)
                                  (finish! (.-selectedUpgrade owner))))
                            (and (.-painted owner) (identical? "waiting" (.-phase owner))
                                 (noticeable? root preview) (not (reduced-motion?)))
                            (do (set! (.-selectedUpgrade owner) (.-selectedReady owner))
                                (stage! "upgrading")
                                (set! (.-frame owner) (js/requestAnimationFrame await-upgrade!)))
                            :else (stage! "ready"))))
                      (do (set! (.-previewReady owner) true)
                          (when (and (.-revealed gate) (not (.-finalReady owner)))
                            (set! (.-frame owner) (js/requestAnimationFrame painted!)))))
                    (gate-ready! image)))
                (fail! [image]
                  (when (owned?)
                    (.add failed image)
                    (when (identical? image final) (stage! "failed"))
                    (when (and (identical? wrapper (.-hero gate)) (identical? (if preview 2 1) (.-size failed))) (reveal!))))
                (decode! [^js image]
                  (when (and (owned?) (.contains root image))
                    (if-not (> (.-naturalWidth image) 0)
                      (fail! image)
                      (let [selected (.-currentSrc image)
                            done! #(when (identical? selected (.-currentSrc image)) (ready! image))]
                        (if (.-decode image)
                          (.then (.decode image) done! #(when (identical? selected (.-currentSrc image)) (fail! image)))
                          (done!))))))
                (departed! [] (when (owned?) (stage! "single")))
                (restored! [^js event]
                  (when (and (.-persisted event) (owned?))
                    (stage! "single")
                    (when (usable? final) (decode! final))))]
          (aset owner "handoff" handoff!)
          (aset owner "current" current?)
          (aset owner "previewPainted" painted!)
          (aset owner "gateFinished" #(when-not (.-staging owner) (cleanup!)))
          (.set owners wrapper owner)
          (.push entries owner)
          (set! (.-observer owner)
                (js/MutationObserver.
                 (fn []
                   (cond
                     (not (owned?)) (do (when (identical? wrapper (.-hero gate)) (reveal!)) (cleanup!))
                     (and preview (.hasAttribute wrapper "data-image-managed"))
                     ;; A terminal handoff never abandons a pending sole hero.
                     (handoff!)))))
          (.observe (.-observer owner) root
                    #js {:subtree true
                         :childList true
                         :attributes true
                         :attributeFilter #js ["src" "data-image-managed"]})
          (.addEventListener js/window "pageshow" restored!)
          (.addEventListener js/window "pagehide" departed!)
          (.addEventListener final "transitionend" transitioned!)
          (.addEventListener final "transitioncancel" transitioned!)
          (stage! (if (or (direct?) (same?)) "single" "waiting"))
          ;; Bind every candidate before an already-complete no-decode image can
          ;; finish synchronously and clean up the whole ownership lifetime.
          (.forEach (.filter #js [preview final] (fn [image] (some? image)))
                    (fn [image]
                      (let [load! #(do (when (and (current?) (same?)) (stage! "single")) (decode! image))
                            error! #(fail! image)]
                        (.push bindings #js [image load! error!])
                        (.addEventListener image "load" load!)
                        (.addEventListener image "error" error!))))
          (.forEach (.filter #js [preview final] (fn [image] (some? image))) #(when (.-complete %) (decode! %))))))))

(defn init! []
  (when-let [root (.querySelector js/document "#app")]
    (skeleton/watch! root)
    (let [owners (js/WeakMap.)
          entries #js []
          bindings #js []
          ^js gate #js {:hero (.querySelector root "#top-banner")
                       :pending (.hasAttribute root "data-document-pending")
                       :positioned false
                       :heroReady false
                       :revealed (not (.hasAttribute root "data-document-pending"))
                       :disposed false}]
      (aset root "__progressiveImages" owners)
      (letfn [(cleanup! []
                (when-not (.-disposed gate)
                  (set! (.-disposed gate) true)
                  (js/clearTimeout (.-deadline gate))
                  (.forEach entries (fn [^js entry] (.gateFinished entry)))
                  (.forEach bindings (fn [binding]
                                       (.removeEventListener (aget binding 0) "load" (aget binding 1))
                                       (.removeEventListener (aget binding 0) "error" (aget binding 2))))))
              (reveal! []
                (set! (.-heroReady gate) true)
                (when (and (.-positioned gate) (not (.-revealed gate)))
                  (set! (.-revealed gate) true)
                  (js/clearTimeout (.-deadline gate))
                  (js/requestAnimationFrame
                   (fn [] (js/requestAnimationFrame
                     (fn []
                       (.removeAttribute root "data-document-pending")
                       ;; Mark preview paint in a following frame, after reveal.
                       (js/requestAnimationFrame #(.forEach entries (fn [^js entry] (.previewPainted entry))))
                       (cleanup!)))))))]
        (if (and (.-pending gate) (not (direct?)))
          (destination/await! root #(do (set! (.-positioned gate) true)
                                        (when (.-heroReady gate) (reveal!))))
          (set! (.-positioned gate) true))
        (set! (.-deadline gate) (js/setTimeout reveal! 10000))
        (.forEach (.querySelectorAll root ".progressive-image, img[data-image-placeholder]")
                  #(acquire! root owners entries gate reveal! %))
        (if-not (.-pending gate)
          (js/clearTimeout (.-deadline gate))
          (let [hero (.-hero gate)]
            (if (or (not hero) (.hasAttribute hero "data-image-placeholder"))
              (reveal!)
              (when-not (.has owners hero)
                (if-let [image (if (.matches hero "img") hero (.querySelector hero "[data-image-final]"))]
                  (let [load! #(if-not (> (.-naturalWidth image) 0)
                                (reveal!)
                                (let [selected (.-currentSrc image)
                                      done! (fn [] (when (identical? selected (.-currentSrc image)) (reveal!)))]
                                  (if (.-decode image) (.then (.decode image) done! reveal!) (done!))))]
                    (.push bindings #js [image load! reveal!])
                    (.addEventListener image "load" load!)
                    (.addEventListener image "error" reveal!)
                    (when (.-complete image) (load!)))
                  (reveal!))))))))))
