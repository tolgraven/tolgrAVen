(ns tolgraven.components.content.frame
  "Generic authored frames reuse the shared sandbox and own only size measurement."
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as r]
            [tolgraven.listener :as listener]
            [tolgraven.components.iframe :as iframe]
            [tolgraven.components.content.layout :as layout]
            [clojure.string :as string]))

(defn local-source? [src]
  (boolean (and (string/starts-with? src "/") (not (string/starts-with? src "//")))))

(defn valid-size-message? [event frame origin]
  (let [data (.-data event)
        height (when (and data (object? data)) (aget data "height"))]
    (and (= origin (.-origin event))
         (identical? (.-source event) (.-contentWindow frame))
         (= "website:iframe-size" (when data (aget data "type")))
         (number? height) (js/Number.isFinite height) (< 0 height 10000))))

(defc <frame>
  "Embed an HTTPS page or local path. Width/height accept auto, fit, rem, em, percent or pixels. Auto height measures safe same-origin documents or accepts a bounded website:iframe-size message from this exact frame and expected origin; other pages keep a fitted fallback. Interactive enables scripts/forms in the existing opaque sandbox. Browser CSP/frame restrictions still apply."
  {:authoring {:category "Media"
               :defaults {:src "/", :title "Embedded page", :layout-width "100%", :layout-height "fit"}}
   :spec-schema [:map [:src [:re "^(?:https://|/[^/]|/$)"]]
                      [:?title :string] [:?interactive :boolean]]
   :args-schema [:cat [:map [:src :string] [:?layout layout/schema]]]}
  [{:keys [src title interactive]}]
  :let [*frame (atom nil)
        *height (r/atom nil)
        *loaded (r/atom nil)]
  (let [geometry (r/use-context layout/geometry-context)
        automatic? (= "auto" (:height geometry))
        local? (and (local-source? src) (not interactive))
        sandbox (if local? "allow-same-origin" (get iframe/sandbox-by-trust (if interactive :trusted :untrusted)))
        expected-origin (when (exists? js/location)
                          (if local? (.-origin js/location) "null"))]
    (r/use-effect
      (fn []
        (when (and automatic? (exists? js/window) @*frame (= src (:src @*loaded)))
          (let [frame @*frame
                *alive? (atom true)
                record! (fn [height]
                          (when @*alive? (reset! *height {:src src
                                            :value (str (/ (min 10000 (max 1 height))
                                                           (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))) "rem")})))
                measure! (fn []
                           (try (when-let [document (.-contentDocument frame)]
                                  (record! (let [body (.-body document)]
                                             (if body (max (.-scrollHeight body) (.-offsetHeight body))
                                                 (.. document -documentElement -scrollHeight)))))
                                (catch :default _ nil)))
                observer (try
                           (when (and local? (exists? js/ResizeObserver) (.-contentDocument frame))
                             (let [observer (js/ResizeObserver. measure!)]
                               (.observe observer (.. frame -contentDocument -documentElement))
                               observer))
                           (catch :default _ nil))
                stop! (listener/listen! :content-frame
                        [{:target js/window
                          :event "message"
                          :handler (fn [event]
                                     (when (valid-size-message? event frame expected-origin)
                                       (record! (aget (.-data event) "height"))))}])]
            (measure!)
            (when-let [window (.-contentWindow frame)]
              ;; An opaque sandbox has origin "null", so the exact window is the
              ;; target and '*' is necessary for this non-sensitive size request.
              (.postMessage window #js {:type "website:measure-request"}
                            (if (= "null" expected-origin) "*" expected-origin)))
            (fn [] (reset! *alive? false) (stop!) (when observer (.disconnect observer))))))
      [src automatic? local? @*loaded])
    [iframe/<iframe>
     {:src src
      :label (or title "Embedded page")
      :trust (if interactive :trusted :untrusted)
      :sandbox sandbox
      :ref (fn [node] (reset! *frame node) nil)
      :on-load #(reset! *loaded {:src src, :generation (str (random-uuid))})
      :style {:display "block"
              :width "100%"
              :max-width "100%"
              :border "none"
              :aspect-ratio (when-not automatic? "16 / 9")
              :height (cond automatic? (if (= src (:src @*height)) (:value @*height) "20rem")
                            (and (:height geometry) (not= "fit" (:height geometry))) "100%"
                            :else "auto")}}]))
