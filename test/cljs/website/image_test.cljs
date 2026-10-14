(ns website.image-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require
    [cljs.core.async]
    [clojure.string :as string]
    [cljs.test :refer-macros [async deftest is]]
    [website.react :as r]
    [website.components.image :as image]
    [website.listener :as listener]
    [website.test-support :as support]
    [website.components.image.sources :as sources]
    [website.components.shell :as shell]
    [website.component.markup :as markup]
    [website.component.restore :as restore]))

(defn- check-original-fallback! [src done]
  (let [container (.createElement js/document "div")
        root (r/create-root container)
        *attrs (r/atom {})
        *errors (atom 0)
        *finished? (atom false)
        *timeout (atom nil)
        *source-reset? (atom false)
        observer (js/MutationObserver.
                   (fn [records]
                     (doseq [record (array-seq records)
                             node (array-seq (.-addedNodes record))]
                       (when (= "SOURCE" (.-nodeName node))
                         (reset! *source-reset? true)))))
        finish! (fn []
                  (when (compare-and-set! *finished? false true)
                    (js/clearTimeout @*timeout)
                    (.disconnect observer)
                    (r/unmount root)
                    (.remove container)
                    (done)))
        fail-image! (fn [element]
                      (.dispatchEvent element (js/Event. "error")))
        changed-src-loaded! (fn [_]
                              (is @*source-reset?
                                  "Changing src on the same component tries modern sources anew")
                              (is (= 1 @*errors))
                              (js/setTimeout finish! 0))
        loaded! (fn [event]
                  (let [element (.-currentTarget event)
                        current-src (.-currentSrc element)]
                    (if (or (.endsWith current-src ".avif")
                            (.endsWith current-src ".webp"))
                      (fail-image! element)
                      (do
                        (is (.endsWith current-src src) "The original JPEG/PNG actually loads")
                        (is (= 0 (.-length (.querySelectorAll container "source")))
                            "A modern image error removes both modern sources")
                        (is (= "Fallback portrait" (.-alt element)))
                        (is (= "portrait" (.-className element)))
                        (is (= 0 @*errors) "A recovered failure does not call the caller's handler")
                        (fail-image! element)
                        (is (= 1 @*errors) "The original failure reaches the caller exactly once")
                        (reset! *source-reset? false)
                        (reset! *attrs {:src "/img/tolgrav-square.png"
                                        :progressive? false
                                        :on-load changed-src-loaded!})))))]
    (.appendChild (.-body js/document) container)
    (.observe observer container #js {:childList true :subtree true})
    (reset! *timeout (js/setTimeout (fn []
                                     (is false "Image fallback completed within fifteen seconds")
                                     (finish!))
                                   ;; Allow cold decodes and throttled background frames;
                                   ;; this still fails if either real load never arrives.
                                   15000))
    ;; Force the selected modern format to fail even when its decoder works.
    ;; In Safari Lockdown Mode, the AVIF error happens naturally instead.
    (reset! *attrs {:src src
                   :progressive? false
                   :alt "Fallback portrait"
                   :class "portrait"
                   :on-load loaded!
                   :on-error (fn [_] (swap! *errors inc))})
    (r/render root [(fn [] [image/<picture> @*attrs])])))

(deftest picture-falls-back-to-png
  (async done
    (check-original-fallback! "/img/tolgrav.png" done)))

(deftest picture-falls-back-to-jpeg
  (async done
    (check-original-fallback! "/img/foggy-shit-small.jpg" done)))

(deftest converted-avatar-falls-back-to-its-original-png
  (async done
    (check-original-fallback!
     (str (.-origin js/location) "/storage/v1/object/public/avatars/test/"
          (apply str (repeat 64 "0")) ".png")
     done)))

(deftest hydration-recovers-a-modern-image-that-failed-before-react-attached
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg" :progressive? false}]]
      ;; Test fixture models the browser's already-failed SSR image. No error
      ;; event will arrive after hydration, so the ref adapter must detect it.
      (set! (.-innerHTML container) (r/render-to-string form))
      (let [img (.querySelector container "img")]
        (doseq [[property value] [["complete" true] ["naturalWidth" 0]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small.avif")]]]
          (js/Object.defineProperty img property #js {:configurable true :value value})))
      (let [root (r/hydrate-root container form)]
        (js/setTimeout
          (fn []
            (r/flush)
            (is (zero? (.-length (.querySelectorAll container "source")))))
          50)
        (js/setTimeout (fn [] (r/unmount root) (done)) 100)))))

(deftest footer-image-reserves-dimensions-and-uses-native-lazy-loading
  (let [html (r/render-to-string
               [shell/<footer-content> [{:id :left
                                         :logo {:src "img/tolgrav-square.png"
                                                :alt "Site logo"}}]])]
    (is (.includes html "width=\"106\""))
    (is (.includes html "height=\"106\""))
    (is (.includes html "tolgrav-square-40w.avif"))
    (is (.includes html "loading=\"lazy\""))))

(deftest native-loading-attributes-survive-component-markup-normalization
  (let [html (r/render-to-string
               (markup/normalize-form
                 [:div {:loading [:p "Component placeholder"]}
                  [:iframe {:src "about:blank" :props {:loading "lazy"}}]
                  [:img.test-image {:src "image.png" :loading "eager"}]]))]
    (is (.includes html "loading=\"lazy\""))
    (is (.includes html "loading=\"eager\""))
    (is (not (.includes html "<div loading=")))))


(deftest progressive-pictures-reserve-one-layout-and-share-responsive-final-candidates
  (let [html (r/render-to-string
               [image/<picture> {:src "/img/foggy-shit-small.jpg"
                                 :id "top-banner"
                                 :class "media media-as-bg"
                                 :alt "Foggy forest"
                                 :loading "eager"
                                 :fetchPriority "high"}])]
    (is (.includes html "class=\"progressive-image media media-as-bg\""))
    (is (.includes html "data-image-preview=\"true\""))
    (is (.includes html "data-image-final=\"true\""))
    (is (.includes html "aria-hidden=\"true\""))
    (is (.includes html "srcSet=\"/img/foggy-shit-small-400w.avif\""))
    (is (.includes html (str "srcSet=\"" (sources/srcset "/img/foggy-shit-small.jpg" "avif") "\"")))
    (is (.includes html "width=\"1189\""))
    (is (not (.includes html "progressive?=")))
    (is (not (.includes html "preview?=")))))

(deftest progressive-hydration-preserves-both-native-image-nodes-while-final-is-pending
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
      (set! (.-innerHTML container) (r/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [preview (.querySelector container "[data-image-preview]")
            final (.querySelector container "[data-image-final]")]
        ;; Keep the adapter's native decode boundary pending during attachment.
        (doseq [[property value] [["complete" false] ["naturalWidth" 0]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (r/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (identical? preview (.querySelector container "[data-image-preview]")))
              (is (identical? final (.querySelector container "[data-image-final]")))
              (is (= "" (.. final -style -visibility)))
              (r/unmount root)
              (.remove container)
              (done))
            100))))))

(deftest decoded-final-removes-the-preview-without-replacing-the-native-image
  (async done
    (let [container (.createElement js/document "div")
          *final (atom nil)
          *finished? (atom false)
          *timeout (atom nil)
          root (r/create-root container)
          finish! (fn []
                    (when (compare-and-set! *finished? false true)
                      (js/clearTimeout @*timeout)
                      (r/unmount root)
                      (.remove container)
                      (done)))
          loaded! (fn [event]
                    (reset! *final (.-currentTarget event))
                    (js/setTimeout
                      (fn []
                        (is (re-find #"wide-spot-ctrl-small(-[0-9]+w)?\.avif$" (.-currentSrc @*final))
                            "The browser may reuse a cached larger final candidate")
                        (is (= 1 (.-length (.querySelectorAll container "img"))))
                        (is (nil? (.querySelector container "[data-image-preview]")))
                        (is (identical? @*final (.querySelector container "[data-image-final]")))
                        (.dispatchEvent js/window (js/PageTransitionEvent. "pageshow" #js {:persisted true}))
                        (is (= 1 (.-length (.querySelectorAll container "img")))
                            "A restored document does not add a redundant preview")
                        (finish!))
                      150))]
      (.appendChild (.-body js/document) container)
      (r/render root [image/<picture> {:src "/img/wide-spot-ctrl-small.jpg"
                                       :progressive? true
                                       :sizes "10vw"
                                       :on-load loaded!}])
      (reset! *timeout
        (js/setTimeout (fn []
                         (when-not @*finished?
                           (is false "Native final image decoded within ten seconds")
                           (finish!)))
                       10000)))))

(deftest progressive-final-failure-retains-the-preview
  (async done
    (let [container (.createElement js/document "div")
          root (r/create-root container)
          *errors (atom 0)]
      (.appendChild (.-body js/document) container)
      (r/render root [image/<picture> {:src "/img/foggy-shit-small.jpg"
                                       :on-error (fn [_] (swap! *errors inc))}])
      (js/setTimeout
        (fn []
          (let [final (.querySelector container "[data-image-final]")
                preview (.querySelector container "[data-image-preview]")]
            ;; Modern-source failure first selects the original.
            (.dispatchEvent final (js/Event. "error"))
            (js/setTimeout
              (fn []
                (.dispatchEvent final (js/Event. "error"))
                (js/setTimeout
                  (fn []
                    (is (= "hidden" (.. final -style -visibility)))
                    (is (some? (.querySelector container "[data-image-preview]")))
                    (is (= 1 @*errors))
                    (.dispatchEvent final (js/Event. "error"))
                    (is (= 1 @*errors) "Repeated original errors do not duplicate notification")
                    (r/unmount root)
                    (.remove container)
                    (done))
                  100))
              100)))
        300))))


(deftest progressive-hydration-keeps-preview-after-an-original-failed-before-attachment
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
      (set! (.-innerHTML container) (r/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [final (.querySelector container "[data-image-final]")
            preview (.querySelector container "[data-image-preview]")]
        ;; Model a browser that selected but failed the original before handlers
        ;; attached, while keeping SSR and first-client markup identical.
        (do
          (doseq [[property value] [["complete" true] ["naturalWidth" 0]
                                   ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small.jpg")]]]
            (js/Object.defineProperty final property #js {:configurable true :value value}))
          (let [root (r/hydrate-root container form)]
            (js/setTimeout
              (fn []
                (is (identical? preview (.querySelector container "[data-image-preview]")))
                (is (= "hidden" (.. final -style -visibility)))
                (r/unmount root)
                (.remove container)
                (done))
              100)))))))


(deftest same-pending-candidate-needs-one-native-image-after-commit
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
      (set! (.-innerHTML container) (r/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [final (.querySelector container "[data-image-final]")]
        (doseq [[property value] [["complete" false] ["naturalWidth" 0]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-400w.avif")]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (r/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (= 1 (.-length (.querySelectorAll container "img"))))
              (is (identical? final (.querySelector container "[data-image-final]")))
              (is (false? (.-complete final)) "No decoded asset is required to eliminate a duplicate")
              (r/unmount root)
              (.remove container)
              (done))
            100))))))

(deftest decoded-phase-belongs-only-to-the-current-source
  (async done
    (let [container (.createElement js/document "div")
          root (r/create-root container)
          *src (r/atom "/img/foggy-shit-small.jpg")
          *first? (atom true)
          capture! (fn [element]
                     (when element
                       (let [first? (compare-and-set! *first? true false)]
                         (doseq [[property value] [["complete" first?]
                                                  ["naturalWidth" (if first? 1189 0)]
                                                  ["currentSrc" (if first?
                                                                  (str (.-origin js/location) "/img/foggy-shit-small.avif")
                                                                  "")]]]
                           (js/Object.defineProperty element property #js {:configurable true :value value}))
                         (js/Object.defineProperty element "decode"
                           #js {:configurable true :value (fn [] (js/Promise.resolve nil))}))))]
      (.appendChild (.-body js/document) container)
      (r/render root [(fn [] [image/<picture> {:src @*src
                                               :progressive? true
                                               :ref capture!}])])
      (js/setTimeout
        (fn []
          (is (= 1 (.-length (.querySelectorAll container "img"))))
          (reset! *src "/img/wide-spot-ctrl-small.jpg")
          (js/setTimeout
            (fn []
              (is (= 2 (.-length (.querySelectorAll container "img"))) "B is pending and needs its preview")
              (reset! *src "/img/foggy-shit-small.jpg")
              (js/setTimeout
                (fn []
                  (is (= 2 (.-length (.querySelectorAll container "img")))
                      "Returning to A cannot reuse its old decoded phase while the resource is pending")
                  (r/unmount root)
                  (.remove container)
                  (done))
                100))
            100))
        100))))


(deftest hydration-adopts-a-terminal-native-upgrade-without-restarting-it
  (async done
    (let [container (.createElement js/document "div")
          src "/img/foggy-shit-small.jpg"
          *references (atom [])
          reference (fn [element]
                      (when element
                        (swap! *references conj [:attach element])
                        #(swap! *references conj [:release element])))
          form [image/<picture> {:src src, :id "top-banner", :ref reference}]]
      (.setAttribute container "id" "app")
      (set! (.-innerHTML container) (r/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [hero (.querySelector container "#top-banner")
            preview (.querySelector hero "[data-image-preview]")
            final (.querySelector hero "[data-image-final]")]
        (let [owners (js/WeakMap.)
              owner #js {:root container :src src :phase "upgrading"
                         :current #(not (.hasAttribute hero "data-image-managed"))}]
          (.set owners hero owner)
          (aset container "__progressiveImages" owners))
        (doseq [[property value] [["complete" true] ["naturalWidth" 800]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-800w.avif")]
                                 ["decode" #(js/Promise.resolve)]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (r/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (identical? preview (.querySelector hero "[data-image-preview]")))
              (is (identical? final (.querySelector hero "[data-image-final]")))
              (is (not (.hasAttribute hero "data-image-managed"))
                  "Hydration cannot take the native mid-fade phase away")
              (set! (.-phase (.get (aget container "__progressiveImages") hero)) "ready")
              (.dispatchEvent container (js/Event. "tolgraven:document-image-ready"))
              (js/setTimeout
                (fn []
                  (is (.hasAttribute hero "data-image-managed"))
                  (is (= 1 (.-length (.querySelectorAll hero "img"))))
                  (is (identical? final (.querySelector hero "[data-image-final]")))
                  (is (= [[:attach final]] @*references)
                      "Terminal adoption retains the caller's native attachment")
                  (is (not-any? #(and (= :progressive-image (:owner %))
                                      (identical? container (:target %)))
                                (vals @listener/*bindings))
                      "Terminal adoption releases the owned listener registry acquisition")
                  (r/unmount root)
                  (is (= [[:attach final] [:release final]] @*references)
                      "Both image adapters forward React19 cleanup exactly once")
                  (.remove container)
                  (done))
                100))
            100))))))

(deftest different-native-candidates-share-a-150ms-overlap-and-100ms-blur
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                stylesheet (.createElement js/document "link")
                *root (atom nil)
                *ready (atom [])
                form [image/<picture> {:src "/img/foggy-shit-small.jpg"
                                      :on-visible-ready #(swap! *ready conj %)}]]
            (.setAttribute container "style" "position:fixed;inset:0 auto auto 0;width:20rem;height:20rem;z-index:999")
            (.appendChild (.-body js/document) container)
            (set! (.-rel stylesheet) "stylesheet")
            (set! (.-href stylesheet) "/css/website/main.min.css")
            (try
              (await! (js/Promise.
                        (fn [resolve reject]
                          (set! (.-onload stylesheet) resolve)
                          (set! (.-onerror stylesheet) reject)
                          (.appendChild (.-head js/document) stylesheet))))
              (set! (.-innerHTML container) (r/render-to-string form))
              (let [preview (.querySelector container "[data-image-preview]")
                    final (.querySelector container "[data-image-final]")]
                (doseq [[image property value] [[final "complete" false]
                                              [final "naturalWidth" 0]
                                              [final "currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-800w.avif")]
                                              [final "decode" #(js/Promise.resolve)]
                                              [preview "naturalWidth" 400]]]
                  (js/Object.defineProperty image property #js {:configurable true :value value}))
                (reset! *root (r/hydrate-root container form))
                (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 100))))
                (let [hero (.querySelector container ".progressive-image")]
                  (is (= "waiting" (.getAttribute hero "data-image-phase")))
                  (is (empty? @*ready) "Pending final pixels do not release preview status")
                  (is (= "0s" (.-transitionDuration (js/getComputedStyle final)))
                      "Pending pixels hide immediately rather than reversing a fade")
                  (js/Object.defineProperty final "complete" #js {:configurable true :value true})
                  (js/Object.defineProperty final "naturalWidth" #js {:configurable true :value 800})
                  (.dispatchEvent final (js/Event. "load"))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 30))))
                  (when-not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
                    (is (= "upgrading" (.getAttribute hero "data-image-phase")))
                    (is (empty? @*ready) "The overlap must finish before readiness notification")
                    (is (= "0.15s" (.-transitionDuration (js/getComputedStyle final))))
                    (is (= "0.1s" (.-transitionDuration (js/getComputedStyle preview))))
                    (is (= "linear" (.-transitionTimingFunction (js/getComputedStyle final))))
                    (is (some? (.querySelector hero ".progressive-image__preview-layer.exiting")))
                    (js/Object.defineProperty final "currentSrc"
                      #js {:configurable true :value (str (.-origin js/location) "/img/foggy-shit-small.avif")})
                    (.dispatchEvent final (js/TransitionEvent. "transitionend"
                                           #js {:bubbles true :propertyName "opacity"}))
                    (is (= "upgrading" (.getAttribute hero "data-image-phase"))
                        "A completion for the prior selected candidate cannot finish the upgrade")
                    (.dispatchEvent final (js/Event. "load")))
                  (is (identical? final (.querySelector hero "[data-image-final]")))
                  (await! (support/wait-for! #(seq @*ready) "Final fade did not notify visible readiness"))
                  (is (= 1 (.-length (.querySelectorAll hero "img"))))
                  (is (= "decoded" (.getAttribute hero "data-image-phase")))
                  (is (= [{:src "/img/foggy-shit-small.jpg"
                           :selected-src (.-currentSrc final)}] @*ready))
                  (is (not-any? #(= :image-visible-ready (:owner %)) (vals @listener/*bindings)))))
              (finally
                (when @*root (r/unmount @*root))
                (.remove stylesheet)
                (.remove container)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest responsive-catalog-defaults-and-modern-originals-share-the-source-contract
  (is (sources/progressive? "/img/logo/tolgraven-logo.png"))
  (is (not (sources/progressive? "/img/logo/icon/react-logo-icon.png")))
  (is (not (sources/progressive? "/img/warning_clojure.webp")))
  (is (not (sources/progressive? "/img/wide-spot-ctrl-small.jpg")))
  (is (sources/progressive? "/img/foggy-shit-small.jpg"))
  (is (= 80 (:width (sources/preview "/img/logo/tolgraven-logo.png"))))
  (is (sources/should-use-modern-formats? "/img/warning_clojure.webp"))
  (is (= "/img/warning_clojure-80w.avif" (:avif (sources/preview "/img/warning_clojure.webp"))))
  (is (.includes (sources/srcset "/img/warning_clojure.webp" "avif") "warning_clojure.avif 165w"))
  (is (not (sources/should-use-modern-formats? "https://cms.example/photo.webp")))
  (with-redefs [sources/responsive {"img/manual.jpg" {:width 1000 :height 600 :sizes [80 640] :progressive? false}}]
    (is (not (sources/progressive? "/img/manual.jpg")))))

(deftest paired-SSR-renders-only-the-appropriate-responsive-final
  (with-redefs [restore/*context (r/atom {:page (restore/page-key) :back? true :local? true})]
    (let [html (r/render-to-string [image/<picture> {:src "/img/logo/tolgraven-logo.png"}])]
      (is (.includes html "data-image-phase=\"single\""))
      (is (.includes html "data-image-final=\"true\""))
      (is (not (.includes html "data-image-preview")))
      (is (.includes html (sources/srcset "/img/logo/tolgraven-logo.png" "avif"))))))

(deftest leaving-with-pending-upgrade-removes-preview-without-replacing-final
  (async done
    (go-promise
      (let [previous @restore/*context
            container (.createElement js/document "div")
            root (r/create-root container)]
        (.appendChild (.-body js/document) container)
        (try
          (reset! restore/*context {})
          (r/render root [image/<picture> {:src "/img/foggy-shit-small.jpg"}])
          (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 60))))
          (let [final (.querySelector container "[data-image-final]")]
            (restore/direct-images!)
            (r/flush)
            (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 60))))
            (is (nil? (.querySelector container "[data-image-preview]")))
            (is (identical? final (.querySelector container "[data-image-final]")))
            (is (#{"single" "decoded"} (.getAttribute (.querySelector container ".progressive-image") "data-image-phase")))
            (is (= "1" (.-opacity (js/getComputedStyle final)))))
          (finally
            (r/unmount root)
            (.remove container)
            (.removeAttribute (.-documentElement js/document) "data-image-direct")
            (reset! restore/*context previous)
            (done)))))))

(deftest document-root-slots-preserve-SSR-image-identifiers-through-hydration
  (async done
    (let [container (.createElement js/document "div")
          form (restore/document-form [image/<picture> {:src "/img/logo/tolgraven-logo.png"}])]
      (.appendChild (.-body js/document) container)
      (set! (.-innerHTML container) (r/render-to-string form))
      (let [wrapper (.querySelector container ".progressive-image")
            key (.getAttribute wrapper "data-image-key")
            properties ["--image-final-opacity" "--image-preview-opacity"
                        "--image-preview-blur" "--image-motion" "--image-placeholder"]
            style (mapv #(.getPropertyValue (.-style wrapper) %) properties)
            root (r/hydrate-root container form)]
        (js/setTimeout
          (fn []
            (is (identical? wrapper (.querySelector container ".progressive-image")))
            (is (= key (.getAttribute wrapper "data-image-key")))
            (is (= style (mapv #(.getPropertyValue (.-style wrapper) %) properties))
                "Image variables retain the exact SSR token; unrelated dev anchor styles are independent")
            (r/unmount root)
            (.remove container)
            (done))
          80)))))


(deftest image-placeholder-is-static-safe-metadata-in-SSR
  (let [src "/img/foggy-shit-small.jpg"
        background (sources/placeholder-background src)
        html (r/render-to-string [image/<picture> {:src src}])]
    (is (string/starts-with? background "linear-gradient(to bottom, #"))
    (is (.includes html "data-image-placeholder=\"true\""))
    (is (.includes html background)))
  (doseq [colors [["red" "#ffffff" "#000000"]
                  ["#FFFFFF" "#ffffff" "#000000"]
                  ["#ffffff" "#000000"]]]
    (with-redefs [sources/responsive {"img/unsafe.jpg" {:placeholder {:colors colors}}}]
      (is (nil? (sources/placeholder-background "/img/unsafe.jpg"))))))


(deftest small-manual-responsive-images-have-no-preview-or-blur-layer
  (let [html (r/render-to-string
              [image/<picture> {:src "/img/warning_clojure.webp"}])]
    (is (not (.includes html "progressive-image")))
    (is (not (.includes html "data-image-preview")))
    (is (.includes html (sources/srcset "/img/warning_clojure.webp" "avif")))))


(deftest cheap-large-banner-keeps-a-static-placeholder-with-one-final-image
  (let [html (r/render-to-string
              [image/<picture> {:src "/img/wide-spot-ctrl-small.jpg"
                                :class "original-banner"}])]
    (is (.includes html "data-image-placeholder=\"true\""))
    (is (.includes html "data-image-phase=\"single\""))
    (is (.includes html "class=\"original-banner\""))
    (is (not (.includes html "progressive-image")))
    (is (not (.includes html "data-image-preview")))
    (is (.includes html (sources/srcset "/img/wide-spot-ctrl-small.jpg" "avif")))))


(deftest visible-readiness-waits-for-native-visibility-and-current-source
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                root (r/create-root container)
                *src (r/atom "/img/icons/generated/solid/check.svg")
                *ready (atom [])
                *loaded (atom 0)
                ready! #(swap! *ready conj %)
                loaded! (fn [_] (swap! *loaded inc))
                render-image (fn [] [image/<picture> {:src @*src
                                                     :style {:width "8rem", :height "8rem"}
                                                     :on-load loaded!
                                                     :on-visible-ready ready!}])]
            (.setAttribute container "style" "position:fixed;inset:0 auto auto 0;width:8rem;height:8rem;z-index:999;visibility:hidden")
            (.appendChild (.-body js/document) container)
            (try
              (is (not (.includes (r/render-to-string [render-image]) "on-visible-ready")))
              (r/render root [render-image])
              (await! (support/wait-for! #(pos? @*loaded) "Native SVG did not load"))
              (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 100))))
              (is (empty? @*ready) "A decoded SVG behind a hidden ancestor is not visible")
              (is (some #(= :image-visible-ready (:owner %)) (vals @listener/*bindings)))
              (reset! *src "/img/tolgrav.png")
              (await! (support/wait-for! #(> @*loaded 1) "Replacement native image did not load"))
              (is (empty? @*ready))
              (set! (.-visibility (.-style container)) "visible")
              (await! (support/wait-for! #(seq @*ready) "Visible final image did not notify"))
              (let [native (.querySelector container "img")]
                (is (= [{:src "/img/tolgrav.png", :selected-src (.-currentSrc native)}] @*ready))
                (.dispatchEvent native (js/Event. "load"))
                (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 80))))
                (is (= 1 (count @*ready)) "Repeated load events cannot notify twice")
                (is (not-any? #(= :image-visible-ready (:owner %)) (vals @listener/*bindings))))
              (set! (.-visibility (.-style container)) "hidden")
              (reset! *src "/img/icons/generated/solid/check.svg")
              (await! (support/wait-for! #(> @*loaded 2) "Returning SVG did not load"))
              (r/unmount root)
              (set! (.-visibility (.-style container)) "visible")
              (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 80))))
              (is (= 1 (count @*ready)) "Unmount cancels a hidden generation's notification")
              (is (not-any? #(= :image-visible-ready (:owner %)) (vals @listener/*bindings)))
              (finally
                (r/unmount root)
                (.remove container)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))

(deftest managed-picture-uses-explicit-query-bearing-renditions
  (let [original {:src "https://media.example/photo?version=2"
                  :width 1600
                  :height 900
                  :bytes 200000
                  :mime "image/jpeg"}
        rendition (fn [format width]
                    {:src (str "https://media.example/" format "?width=" width)
                     :width width
                     :height (js/Math.round (* width (/ 9 16)))
                     :bytes 10000
                     :mime (str "image/" format)})
        asset {:id "photo"
               :src (:src original)
               :width 1600
               :height 900
               :renditions {:original original
                            :avif [(rendition "avif" 400) (rendition "avif" 1600)]
                            :webp [(rendition "webp" 400) (rendition "webp" 1600)]}
               :placeholder {:colors ["#112233" "#445566" "#778899"]}}
        container (.createElement js/document "div")]
    (set! (.-innerHTML container)
          (r/render-to-string [image/<picture> {:src "old-reference.jpg"
                                                     :asset asset
                                                     :sizes "45vw"
                                                     :alt "Managed photo"}]))
    (let [final (.querySelector container "img[data-image-final]")
          preview (.querySelector container "img[data-image-preview]")
          avif (.querySelector (.-parentElement final) "source[type='image/avif']")]
      (is (some? preview) "Eligible metadata enables a progressive preview")
      (is (= (:src original) (.getAttribute final "src")))
      (is (= "https://media.example/avif?width=400 400w, https://media.example/avif?width=1600 1600w"
             (.getAttribute avif "srcset")))
      (is (= "45vw" (.getAttribute avif "sizes")))
      (is (= "1600" (.getAttribute final "width")))
      (is (not (.hasAttribute final "asset")))
      (is (not (.hasAttribute final "renditions")))
      (is (string/includes? (.getAttribute (.querySelector container ".progressive-image") "style")
                            "#445566")))))

(deftest managed-svg-retains-one-original-image
  (let [original {:src "https://media.example/logo.svg?version=2"
                  :width 1600
                  :height 900
                  :bytes 10000
                  :mime "image/svg+xml"}
        asset {:id "logo"
               :src (:src original)
               :width 1600
               :height 900
               :renditions {:original original}}
        container (.createElement js/document "div")]
    (set! (.-innerHTML container)
          (r/render-to-string [image/<picture> {:asset asset :alt "Vector logo"}]))
    (is (= 1 (.-length (.querySelectorAll container "img"))))
    (is (nil? (.querySelector container "source, .progressive-image")))
    (is (= (:src original) (.getAttribute (.querySelector container "img") "src")))))
