(ns tolgraven.components.link-preview
  (:require
    [clojure.string :as string]
    [reagent.core :as r]
    [tolgraven.components.iframe :as iframe]
    [tolgraven.components.popover :as popover]
    [tolgraven.routes :as routes]
    [tolgraven.util :as util]))

(def ^:private open-delay-ms 300)
(def ^:private close-delay-ms 180)
(def ^:private navigation-delay-ms 420)
(def ^:private prefetch-delay-ms
  {:trusted 250
   :user 1500})

(defonce *link-inventory (atom {}))
(defonce *preview-providers (atom {}))

(defn link-inventory
  "Return cached external-link counts, trust, and prefetch state by page."
  []
  @*link-inventory)

(defn register-provider!
  "Register a host renderer receiving preview data and an on-load callback."
  [host renderer]
  (swap! *preview-providers assoc host renderer))

(defn- page-key []
  (str (.-pathname js/window.location) (.-search js/window.location)))

(defn- closest [target selector]
  (when (and target (.-closest target))
    (.closest target selector)))

(defn- closest-link [event]
  (closest (.-target event) "a[href]"))

(defn- node-has-page-link? [node]
  (and (.-querySelector node)
       (not (or (closest node "[data-popover]")
                (.querySelector node "[data-popover]")))
       (or (and (.-matches node) (.matches node "a[href]"))
           (.querySelector node "a[href]"))))

(defn- trust-for [link]
  (or (some-> (closest link "[data-link-trust]")
              (.getAttribute "data-link-trust")
              keyword)
      :untrusted))

(defn- direct-link? [link]
  (or (.hasAttribute link "download")
      (= "_blank" (.-target link))
      (.hasAttribute link "data-no-preview")
      (.hasAttribute link "data-popover-direct")))

(defn- previewable-link? [link]
  (and link
       (not (direct-link? link))
       (routes/external-http-url? (.-href link) (.-href js/window.location))))

(defn- preview-data [link]
  {:anchor link
   :title (string/trim (.-textContent link))
   :trust (trust-for link)
   :url (.-href link)})

(defn- unmodified-primary-click? [event]
  (and (zero? (.-button event))
       (not (.-altKey event))
       (not (.-ctrlKey event))
       (not (.-metaKey event))
       (not (.-shiftKey event))))

(defn- reduced-motion? []
  (.matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))

(defn- attach-anchor! [link]
  (.setProperty (.-style link) "anchor-name" popover/anchor-name))

(defn- detach-anchor! [link]
  (when link
    (.removeProperty (.-style link) "anchor-name")))

(defn- prefetch! [url trust]
  (when (and (get prefetch-delay-ms trust)
             (not (get-in @*link-inventory [(page-key) :links url :prefetched?])))
    (let [el (.createElement js/document "link")]
      (set! (.-rel el) "prefetch")
      (set! (.-href el) url)
      (.setAttribute el "as" "document")
      (.appendChild js/document.head el)
      (swap! *link-inventory assoc-in
             [(page-key) :links url :prefetched?] true))))

(defn- inventory-page!
  "Cache an approximate per-page external-link inventory and observe candidates."
  [observer]
  (let [links (filter previewable-link?
                      (array-seq (.querySelectorAll js/document "a[href]")))
        inventory (reduce (fn [result link]
                            (let [url (.-href link)]
                              (assoc result url
                                     (merge (get-in @*link-inventory
                                                    [(page-key) :links url])
                                            {:trust (trust-for link)}))))
                          {}
                          links)]
    (swap! *link-inventory assoc (page-key)
           {:count (count links)
            :links inventory})
    (doseq [link links]
      (.observe observer link))))

(defn- default-preview [{:keys [title trust url]} on-load]
  [iframe/<iframe>
   {:label (str "Preview of " (if (string/blank? title) url title))
    :on-load on-load
    :src url
    :trust trust}])

(defn- <preview-content> [data on-load]
  (let [host (.-host (js/URL. (:url data)))
        renderer (get @*preview-providers host default-preview)]
    [renderer data on-load]))

(defn <link-preview>
  "Delegated link previews. Generic popover and iframe concerns remain separate."
  []
  (let [*preview (r/atom nil)
        *loaded? (r/atom false)
        *expanded? (r/atom false)
        *touch-open-link (atom nil)
        *prefetch-queue (atom [])
        *prefetch-timer (atom nil)
        *inventory-frame (atom nil)
        timers {:show (atom nil)
                :hide (atom nil)
                :navigation (atom nil)}
        clear-timer! (fn [timer]
                       (when-let [id @(get timers timer)]
                         (js/clearTimeout id)
                         (reset! (get timers timer) nil)))
        cancel-close! #(clear-timer! :hide)
        close! (fn []
                 (when-not @*expanded?
                   (doseq [timer (keys timers)]
                     (clear-timer! timer))
                   (detach-anchor! (:anchor @*preview))
                   (reset! *loaded? false)
                   (reset! *preview nil)))
        schedule-close! (fn []
                          (clear-timer! :hide)
                          (reset! (:hide timers)
                                  (js/setTimeout close! close-delay-ms)))
        show-now! (fn [link]
                    (doseq [timer [:show :hide]]
                      (clear-timer! timer))
                    (detach-anchor! (:anchor @*preview))
                    (attach-anchor! link)
                    (reset! *loaded? false)
                    (reset! *preview (preview-data link)))
        show! (fn [link]
                (doseq [timer [:show :hide]]
                  (clear-timer! timer))
                (reset! (:show timers)
                        (js/setTimeout #(show-now! link) open-delay-ms)))
        navigate! (fn [{:keys [url] :as data}]
                    (doseq [timer (keys timers)]
                      (clear-timer! timer))
                    (reset! *preview data)
                    (reset! *expanded? true)
                    (reset! (:navigation timers)
                            (js/setTimeout
                              #(.assign js/window.location url)
                              (if (reduced-motion?) 0 navigation-delay-ms))))
        inside-preview? #(boolean (closest % "[data-popover]"))
        on-pointer-over (fn [event]
                          (when-not (= "touch" (.-pointerType event))
                            (if-let [link (closest-link event)]
                              (when (and (previewable-link? link)
                                         (not= link
                                               (closest (.-relatedTarget event)
                                                        "a[href]")))
                                (show! link))
                              (when (inside-preview? (.-target event))
                                (cancel-close!)))))
        on-pointer-out (fn [event]
                         (when-not (= "touch" (.-pointerType event))
                           (let [link (closest-link event)
                                 preview? (inside-preview? (.-target event))
                                 related (.-relatedTarget event)]
                             (when (and (or link preview?)
                                        (not (or (= link (closest related "a[href]"))
                                                 (and preview?
                                                      (inside-preview? related)))))
                               (schedule-close!)))))
        on-focus-in (fn [event]
                      (if-let [link (closest-link event)]
                        (when (previewable-link? link)
                          (show! link))
                        (when (inside-preview? (.-target event))
                          (cancel-close!))))
        on-focus-out (fn [event]
                       (when (and (or (closest-link event)
                                      (inside-preview? (.-target event)))
                                  (not (inside-preview? (.-relatedTarget event))))
                         (schedule-close!)))
        on-key-down (fn [event]
                      (when (and (= "Escape" (.-key event))
                                 (not @*expanded?))
                        (close!)))
        on-pointer-down (fn [event]
                          (let [link (closest-link event)]
                            (reset! *touch-open-link
                                    (when (and (= "touch" (.-pointerType event))
                                               (previewable-link? link)
                                               (not= (.-href link)
                                                     (:url @*preview)))
                                      link))))
        on-click (fn [event]
                   (when-let [link (closest-link event)]
                     (cond
                       (= link @*touch-open-link)
                       (do
                         (.preventDefault event)
                         (.stopImmediatePropagation event)
                         (show-now! link))

                       (and (previewable-link? link)
                            (= (.-href link) (:url @*preview))
                            (unmodified-primary-click? event))
                       (do
                         (.preventDefault event)
                         (navigate! @*preview))))
                   (reset! *touch-open-link nil))
        on-popover-click (fn [event]
                           (when (and (unmodified-primary-click? event)
                                      (not (closest (.-target event)
                                                    "[data-popover-direct]")))
                             (.preventDefault event)
                             (navigate! @*preview)))
        pump-prefetch!
        (fn pump-prefetch! []
          (if-let [{:keys [trust url]} (first @*prefetch-queue)]
            (do
              (swap! *prefetch-queue subvec 1)
              (prefetch! url trust)
              (reset! *prefetch-timer
                      (js/setTimeout pump-prefetch!
                                     (get prefetch-delay-ms trust))))
            (reset! *prefetch-timer nil)))
        enqueue-prefetch! (fn [link]
                            (let [url (.-href link)
                                  trust (trust-for link)]
                              (when (and (get prefetch-delay-ms trust)
                                         (not (get-in @*link-inventory
                                                      [(page-key) :links url :queued?])))
                                (swap! *link-inventory assoc-in
                                       [(page-key) :links url :queued?] true)
                                (swap! *prefetch-queue conj
                                       {:trust trust :url url})
                                (when-not @*prefetch-timer
                                  (pump-prefetch!)))))
        listeners {"pointerover" on-pointer-over
                   "pointerout" on-pointer-out
                   "focusin" on-focus-in
                   "focusout" on-focus-out
                   "keydown" on-key-down}
        observer (js/IntersectionObserver.
                   (fn [entries]
                     (doseq [entry entries
                             :when (.-isIntersecting entry)
                             :let [link (.-target entry)]]
                       (enqueue-prefetch! link)
                       (.unobserve observer link)))
                   #js {:rootMargin "25%"})
        mutation-observer
        (js/MutationObserver.
          (fn [mutations]
            (when (and (not @*inventory-frame)
                       (some (fn [mutation]
                               (some node-has-page-link?
                                     (concat (array-seq (.-addedNodes mutation))
                                             (array-seq (.-removedNodes mutation)))))
                             mutations))
              (reset! *inventory-frame
                      (js/requestAnimationFrame
                        #(do
                           (reset! *inventory-frame nil)
                           (inventory-page! observer)))))))]
    (r/create-class
      {:display-name "Link preview"
       :component-did-mount
       (fn [_]
         (doseq [[event handler] listeners]
           (util/on-document event handler))
         (util/on-document "pointerdown" on-pointer-down {:capture true})
         (util/on-document "click" on-click {:capture true})
         (inventory-page! observer)
         (.observe mutation-observer js/document.body
                   #js {:childList true :subtree true}))
       :component-will-unmount
       (fn [_]
         (doseq [[event handler] listeners]
           (util/remove-on-document event handler))
         (util/remove-on-document "pointerdown" on-pointer-down {:capture true})
         (util/remove-on-document "click" on-click {:capture true})
         (doseq [timer (keys timers)]
           (clear-timer! timer))
         (when @*prefetch-timer
           (js/clearTimeout @*prefetch-timer))
         (when @*inventory-frame
           (js/cancelAnimationFrame @*inventory-frame))
         (detach-anchor! (:anchor @*preview))
         (.disconnect observer)
         (.disconnect mutation-observer))
       :reagent-render
       (fn []
         (when-let [{:keys [title url] :as data} @*preview]
           [popover/<popover>
            {:aria-label (str "Preview of " (if (string/blank? title) url title))
             :class "link-preview"
             :expanded? @*expanded?
             :on-click on-popover-click
             :on-pointer-enter cancel-close!
             :on-pointer-leave schedule-close!
             :open? true}
            [:<>
             [:div.link-preview__bar
              [:a.link-preview__link
               {:data-popover-direct true
                :href url}
               (if (string/blank? title) url title)]
              [:span.link-preview__hint "Click to open"]]
             [:div.link-preview__viewport
              [<preview-content> data #(reset! *loaded? true)]
              (when-not @*loaded?
                [:div.link-preview__loading
                 [:i.fa.fa-spinner.fa-spin]
                 [:span "Loading preview"]])
              [:div.link-preview__shield
               {:aria-hidden true}]]]]))})))
