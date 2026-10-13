(ns tolgraven.image-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require
    [cljs.core.async]
    [cljs.test :refer-macros [async deftest is]]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [reagent.dom.server :as server]
    [tolgraven.components.image :as image]
    [tolgraven.listener :as listener]
    [tolgraven.components.image.sources :as sources]
    [tolgraven.components.shell :as shell]
    [tolgraven.component.markup :as markup]))

(defn- check-original-fallback! [src done]
  (let [container (.createElement js/document "div")
        root (dom/create-root container)
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
                    (dom/unmount root)
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
    (dom/render root [(fn [] [image/<picture> @*attrs])])))

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
      (set! (.-innerHTML container) (server/render-to-string form))
      (let [img (.querySelector container "img")]
        (doseq [[property value] [["complete" true] ["naturalWidth" 0]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small.avif")]]]
          (js/Object.defineProperty img property #js {:configurable true :value value})))
      (let [root (dom/hydrate-root container form)]
        (js/setTimeout
          (fn []
            (r/flush)
            (is (zero? (.-length (.querySelectorAll container "source")))))
          50)
        (js/setTimeout (fn [] (dom/unmount root) (done)) 100)))))

(deftest footer-image-reserves-dimensions-and-uses-native-lazy-loading
  (let [html (server/render-to-string
               [shell/<footer-content> [{:id :left
                                         :logo {:src "img/tolgrav-square.png"
                                                :alt "Site logo"}}]])]
    (is (.includes html "width=\"106\""))
    (is (.includes html "height=\"106\""))
    (is (.includes html "tolgrav-square-40w.avif"))
    (is (.includes html "loading=\"lazy\""))))

(deftest native-loading-attributes-survive-component-markup-normalization
  (let [html (server/render-to-string
               (markup/normalize-form
                 [:div {:loading [:p "Component placeholder"]}
                  [:iframe {:src "about:blank" :props {:loading "lazy"}}]
                  [:img.test-image {:src "image.png" :loading "eager"}]]))]
    (is (.includes html "loading=\"lazy\""))
    (is (.includes html "loading=\"eager\""))
    (is (not (.includes html "<div loading=")))))


(deftest progressive-pictures-reserve-one-layout-and-share-responsive-final-candidates
  (let [html (server/render-to-string
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
      (set! (.-innerHTML container) (server/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [preview (.querySelector container "[data-image-preview]")
            final (.querySelector container "[data-image-final]")]
        ;; Keep the adapter's native decode boundary pending during attachment.
        (doseq [[property value] [["complete" false] ["naturalWidth" 0]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (dom/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (identical? preview (.querySelector container "[data-image-preview]")))
              (is (identical? final (.querySelector container "[data-image-final]")))
              (is (= "" (.. final -style -visibility)))
              (dom/unmount root)
              (.remove container)
              (done))
            100))))))

(deftest decoded-final-removes-the-preview-without-replacing-the-native-image
  (async done
    (let [container (.createElement js/document "div")
          *final (atom nil)
          *finished? (atom false)
          *timeout (atom nil)
          root (dom/create-root container)
          finish! (fn []
                    (when (compare-and-set! *finished? false true)
                      (js/clearTimeout @*timeout)
                      (dom/unmount root)
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
      (dom/render root [image/<picture> {:src "/img/wide-spot-ctrl-small.jpg"
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
          root (dom/create-root container)
          *errors (atom 0)]
      (.appendChild (.-body js/document) container)
      (dom/render root [image/<picture> {:src "/img/foggy-shit-small.jpg"
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
                    (dom/unmount root)
                    (.remove container)
                    (done))
                  100))
              100)))
        300))))


(deftest progressive-hydration-keeps-preview-after-an-original-failed-before-attachment
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
      (set! (.-innerHTML container) (server/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [final (.querySelector container "[data-image-final]")
            preview (.querySelector container "[data-image-preview]")]
        ;; Model a browser that selected but failed the original before handlers
        ;; attached, while keeping SSR and first-client markup identical.
        (do
          (doseq [[property value] [["complete" true] ["naturalWidth" 0]
                                   ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small.jpg")]]]
            (js/Object.defineProperty final property #js {:configurable true :value value}))
          (let [root (dom/hydrate-root container form)]
            (js/setTimeout
              (fn []
                (is (identical? preview (.querySelector container "[data-image-preview]")))
                (is (= "hidden" (.. final -style -visibility)))
                (dom/unmount root)
                (.remove container)
                (done))
              100)))))))


(deftest same-pending-candidate-needs-one-native-image-after-commit
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
      (set! (.-innerHTML container) (server/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [final (.querySelector container "[data-image-final]")]
        (doseq [[property value] [["complete" false] ["naturalWidth" 0]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-400w.avif")]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (dom/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (= 1 (.-length (.querySelectorAll container "img"))))
              (is (identical? final (.querySelector container "[data-image-final]")))
              (is (false? (.-complete final)) "No decoded asset is required to eliminate a duplicate")
              (dom/unmount root)
              (.remove container)
              (done))
            100))))))

(deftest decoded-phase-belongs-only-to-the-current-source
  (async done
    (let [container (.createElement js/document "div")
          root (dom/create-root container)
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
      (dom/render root [(fn [] [image/<picture> {:src @*src
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
                  (dom/unmount root)
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
      (.setAttribute container "data-document-image-owner" "native")
      (.setAttribute container "data-document-image-phase" "upgrading")
      (.setAttribute container "data-document-image-src" src)
      (set! (.-innerHTML container) (server/render-to-string form))
      (.appendChild (.-body js/document) container)
      (let [hero (.querySelector container "#top-banner")
            preview (.querySelector hero "[data-image-preview]")
            final (.querySelector hero "[data-image-final]")]
        (set! (.-__progressiveHero container) hero)
        (doseq [[property value] [["complete" true] ["naturalWidth" 800]
                                 ["currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-800w.avif")]
                                 ["decode" #(js/Promise.resolve)]]]
          (js/Object.defineProperty final property #js {:configurable true :value value}))
        (let [root (dom/hydrate-root container form)]
          (js/setTimeout
            (fn []
              (is (identical? preview (.querySelector hero "[data-image-preview]")))
              (is (identical? final (.querySelector hero "[data-image-final]")))
              (is (not (.hasAttribute hero "data-image-managed"))
                  "Hydration cannot take the native mid-fade phase away")
              (.setAttribute container "data-document-image-phase" "ready")
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
                  (dom/unmount root)
                  (is (= [[:attach final] [:release final]] @*references)
                      "Both image adapters forward React19 cleanup exactly once")
                  (.remove container)
                  (done))
                100))
            100))))))

(deftest different-native-candidates-share-a-100ms-overlapping-exit
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                stylesheet (.createElement js/document "link")
                *root (atom nil)
                form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
            (.appendChild (.-body js/document) container)
            (set! (.-rel stylesheet) "stylesheet")
            (set! (.-href stylesheet) "/css/tolgraven/main.min.css")
            (try
              (await! (js/Promise.
                        (fn [resolve reject]
                          (set! (.-onload stylesheet) resolve)
                          (set! (.-onerror stylesheet) reject)
                          (.appendChild (.-head js/document) stylesheet))))
              (set! (.-innerHTML container) (server/render-to-string form))
              (let [preview (.querySelector container "[data-image-preview]")
                    final (.querySelector container "[data-image-final]")]
                (doseq [[image property value] [[final "complete" false]
                                              [final "naturalWidth" 0]
                                              [final "currentSrc" (str (.-origin js/location) "/img/foggy-shit-small-800w.avif")]
                                              [final "decode" #(js/Promise.resolve)]
                                              [preview "naturalWidth" 400]]]
                  (js/Object.defineProperty image property #js {:configurable true :value value}))
                (reset! *root (dom/hydrate-root container form))
                (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 100))))
                (let [hero (.querySelector container ".progressive-image")]
                  (is (= "waiting" (.getAttribute hero "data-image-phase")))
                  (is (= "0s" (.-transitionDuration (js/getComputedStyle final)))
                      "Pending pixels hide immediately rather than reversing a fade")
                  (js/Object.defineProperty final "complete" #js {:configurable true :value true})
                  (js/Object.defineProperty final "naturalWidth" #js {:configurable true :value 800})
                  (.dispatchEvent final (js/Event. "load"))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 30))))
                  (when-not (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)"))
                    (is (= "upgrading" (.getAttribute hero "data-image-phase")))
                    (is (= "0.1s" (.-transitionDuration (js/getComputedStyle final))))
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
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 250))))
                  (is (= 1 (.-length (.querySelectorAll hero "img"))))
                  (is (= "decoded" (.getAttribute hero "data-image-phase")))))
              (finally
                (when @*root (dom/unmount @*root))
                (.remove stylesheet)
                (.remove container)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
