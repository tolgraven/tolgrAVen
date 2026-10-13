(ns tolgraven.image-test
  (:require
    [cljs.test :refer-macros [async deftest is]]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [reagent.dom.server :as server]
    [tolgraven.components.image :as image]
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
          root (dom/create-root container)
          finish! (fn []
                    (when (compare-and-set! *finished? false true)
                      (dom/unmount root)
                      (.remove container)
                      (done)))
          loaded! (fn [event]
                    (reset! *final (.-currentTarget event))
                    (js/setTimeout
                      (fn []
                        (is (.endsWith (.-currentSrc @*final) "-400w.avif"))
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
      (js/setTimeout (fn []
                       (when-not @*finished?
                         (is false "Native final image decoded within ten seconds")
                         (finish!)))
                     10000))))

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
