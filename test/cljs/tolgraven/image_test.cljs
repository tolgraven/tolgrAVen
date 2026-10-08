(ns tolgraven.image-test
  (:require
    [cljs.test :refer-macros [async deftest is]]
    [reagent.core :as r]
    [reagent.dom.client :as dom]
    [reagent.dom.server :as server]
    [tolgraven.components.image :as image]))

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

(deftest hydration-recovers-a-modern-image-that-failed-before-react-attached
  (async done
    (let [container (.createElement js/document "div")
          form [image/<picture> {:src "/img/foggy-shit-small.jpg"}]]
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
