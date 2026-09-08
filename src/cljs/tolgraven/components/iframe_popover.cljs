(ns tolgraven.components.iframe-popover
  (:require
    [clojure.string :as string]
    [reagent.core :as r]
    [tolgraven.components.popover :as popover]))

(def ^:private open-delay-ms 300)
(def ^:private close-delay-ms 180)
(def ^:private navigation-delay-ms 420)

(defn external-http-url?
  "True when href resolves to an HTTP(S) URL on a different origin."
  [href base-url]
  (try
    (let [url (js/URL. href base-url)]
      (and (#{"http:" "https:"} (.-protocol url))
           (not= (.-origin url) (.-origin (js/URL. base-url)))))
    (catch :default _
      false)))

(defn- closest-link [event]
  (let [target (.-target event)]
    (when (and target (.-closest target))
      (.closest target "a[href]"))))

(defn- direct-link? [link]
  (or (.hasAttribute link "download")
      (= "_blank" (.-target link))
      (.hasAttribute link "data-no-preview")
      (.hasAttribute link "data-popover-direct")))

(defn- previewable-link? [link]
  (and link
       (not (direct-link? link))
       (external-http-url? (.-href link) (.-href js/window.location))))

(defn- rect->map [rect]
  {:bottom (.-bottom rect)
   :left (.-left rect)
   :right (.-right rect)
   :top (.-top rect)})

(defn- unmodified-primary-click? [event]
  (and (zero? (.-button event))
       (not (.-altKey event))
       (not (.-ctrlKey event))
       (not (.-metaKey event))
       (not (.-shiftKey event))))

(defn <iframe-popover>
  "Preview external links on hover and animate same-tab navigation."
  []
  (let [*preview (r/atom nil)
        *loaded? (r/atom false)
        *expanded? (r/atom false)
        *show-timer (atom nil)
        *hide-timer (atom nil)
        clear-timer! (fn [timer]
                       (when @timer
                         (js/clearTimeout @timer)
                         (reset! timer nil)))
        cancel-close! #(clear-timer! *hide-timer)
        close! (fn []
                 (clear-timer! *show-timer)
                 (clear-timer! *hide-timer)
                 (reset! *loaded? false)
                 (reset! *expanded? false)
                 (reset! *preview nil))
        schedule-close! (fn []
                          (clear-timer! *hide-timer)
                          (reset! *hide-timer
                                  (js/setTimeout close! close-delay-ms)))
        show! (fn [link]
                (clear-timer! *hide-timer)
                (clear-timer! *show-timer)
                (reset! *show-timer
                        (js/setTimeout
                          #(do
                             (reset! *loaded? false)
                             (reset! *preview
                                     {:anchor-rect
                                      (rect->map (.getBoundingClientRect link))
                                      :title (string/trim (.-textContent link))
                                      :url (.-href link)}))
                          open-delay-ms)))
        on-pointer-over (fn [event]
                          (if-let [link (closest-link event)]
                            (when (previewable-link? link)
                              (show! link))
                            (when (some-> (.-target event)
                                          (.closest "[data-link-preview-popover]"))
                              (cancel-close!))))
        on-pointer-out (fn [event]
                         (when (or (closest-link event)
                                   (some-> (.-target event)
                                           (.closest "[data-link-preview-popover]")))
                           (schedule-close!)))
        on-focus-in (fn [event]
                      (when-let [link (closest-link event)]
                        (when (previewable-link? link)
                          (show! link))))
        on-focus-out (fn [event]
                       (when (closest-link event)
                         (schedule-close!)))
        on-click (fn [event]
                   (when-let [link (closest-link event)]
                     (when (and (previewable-link? link)
                                (unmodified-primary-click? event))
                       (.preventDefault event)
                       (clear-timer! *show-timer)
                       (reset! *preview
                               {:anchor-rect
                                (rect->map (.getBoundingClientRect link))
                                :title (string/trim (.-textContent link))
                                :url (.-href link)})
                       (reset! *expanded? true)
                       (js/setTimeout
                         #(.assign js/window.location (.-href link))
                         navigation-delay-ms))))]
    (r/create-class
      {:display-name "External link iframe preview"
       :component-did-mount
       (fn []
         (.addEventListener js/document "pointerover" on-pointer-over)
         (.addEventListener js/document "pointerout" on-pointer-out)
         (.addEventListener js/document "focusin" on-focus-in)
         (.addEventListener js/document "focusout" on-focus-out)
         (.addEventListener js/document "click" on-click true))
       :component-will-unmount
       (fn []
         (clear-timer! *show-timer)
         (clear-timer! *hide-timer)
         (.removeEventListener js/document "pointerover" on-pointer-over)
         (.removeEventListener js/document "pointerout" on-pointer-out)
         (.removeEventListener js/document "focusin" on-focus-in)
         (.removeEventListener js/document "focusout" on-focus-out)
         (.removeEventListener js/document "click" on-click true))
       :reagent-render
       (fn []
         (when-let [{:keys [anchor-rect title url]} @*preview]
           [popover/<popover>
            {:anchor-rect anchor-rect
             :class "iframe-popover"
             :expanded? @*expanded?
             :height 320
             :on-pointer-enter cancel-close!
             :on-pointer-leave schedule-close!
             :open? true
             :width 500}
            [:<>
             [:div.iframe-popover__bar
              [:a.iframe-popover__link
               {:data-popover-direct true
                :href url}
               (if (string/blank? title) url title)]
              [:span.iframe-popover__hint "Click to open"]]
             [:div.iframe-popover__viewport
              [:iframe
               {:aria-label (str "Preview of " (if (string/blank? title) url title))
                :on-load #(reset! *loaded? true)
                :referrer-policy "no-referrer"
                :sandbox "allow-forms allow-popups allow-popups-to-escape-sandbox allow-scripts"
                :src url
                :tab-index -1}]
              (when-not @*loaded?
                [:div.iframe-popover__loading
                 [:i.fa.fa-spinner.fa-spin]
                 [:span "Loading preview"]])
              [:div.iframe-popover__shield
               {:aria-hidden true}]]]]))})))
