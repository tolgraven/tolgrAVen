(ns tolgraven.modules.link-preview.views
  (:require
    [clojure.string :as string]
    [tolgraven.react :as rf]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.component.registry]
    [reagent.core :as r]
    [tolgraven.component :as component]
    [tolgraven.component.data :as data]
    [tolgraven.modules.link-preview.article :as article]
    [tolgraven.render-context :as context]
    [tolgraven.ssr.local :as local-page]
    [tolgraven.components.iframe :as iframe]
    [tolgraven.components.popover :as popover]
    [tolgraven.components.portal :as portal]
    [tolgraven.component.motion :as motion]
    [tolgraven.modules.link-preview.subs]
    [tolgraven.modules.link-preview.util :as util]
    [tolgraven.components.ui :as ui]
    [tolgraven.util :as dom]))

(def ^:private open-delay-ms 300)
(def ^:private close-delay-ms 180)
(def ^:private navigation-delay-ms 420)
(def ^:private transition-storage-key "tolgraven.modules.link-preview.transition")

(defonce *containers (atom {}))
(defonce *link-elements (atom {}))
(defonce *preview-providers (atom {}))
(defonce *interaction-timers (atom {}))
(defonce *anchor-selector (r/atom nil))

(declare unregister-container!)

(defn- preview-state []
  ;; Browser interaction callbacks read through the safe subscription lifecycle;
  ;; the controller itself owns an ordinary reactive subscription while mounted.
  @(rf/sub [:link-preview/state]))

(defn register-provider!
  "Register a host renderer receiving preview data and an on-load callback."
  [host renderer]
  (swap! *preview-providers assoc host renderer))

(defn- page-key []
  (str (.-pathname js/window.location) (.-search js/window.location)))

(defn- closest [target selector]
  (when (and target (.-closest target))
    (.closest target selector)))

(defn- external-link? [link]
  (and link
       (not (.hasAttribute link "download"))
       (not= "_blank" (.-target link))
       (not (.hasAttribute link "data-no-preview"))
       (not (.hasAttribute link "data-popover-direct"))
       (util/external-http-url? (.-href link)
                                (.-href js/window.location))))

(defn- trust-for [element fallback]
  (or fallback
      (some-> (closest element "[data-link-trust]")
              (.getAttribute "data-link-trust")
              keyword)
      :untrusted))

(defn- candidate [container-id candidate-id link trust]
  {:candidate-id candidate-id
   :container-id container-id
   :origin-page (page-key)
   :title (string/trim (.-textContent link))
   :trust trust
   :url (.-href link)})

(defn candidate-observer
  "Create an observer for one candidate-bearing container."
  [id]
  (when (exists? js/IntersectionObserver)
    (js/IntersectionObserver.
    (fn [entries observer]
    (doseq [entry entries
            :when (.-isIntersecting entry)
            :let [link (.-target entry)
                  data (get-in @*containers
                               [id :candidates-by-element link])]
            :when data]
      (rf/dispatch [:link-preview/visible data])
      (.unobserve observer link)))
    #js {:rootMargin "25%"})))

(defn- clear-interaction-timer! [kind]
  (when-let [timer (get @*interaction-timers kind)]
    (js/clearTimeout timer)
    (swap! *interaction-timers dissoc kind)))

(defn- schedule! [kind delay f]
  (clear-interaction-timer! kind)
  (swap! *interaction-timers assoc kind
         (js/setTimeout
           #(do (swap! *interaction-timers dissoc kind)
                (f))
           delay)))

(defn- element-selector
  "Locate a generated Markdown anchor without mutating its React-owned props."
  [element]
  (loop [element element parts ()]
    (if (and element (not= element js/document.documentElement))
      (let [siblings (some-> element .-parentElement .-children array-seq)
            index (first (keep-indexed #(when (identical? %2 element) (inc %1)) siblings))]
        (when index
          (recur (.-parentElement element) (conj parts (str ":nth-child(" index ")")))))
      (when element (string/join " > " (cons "html" parts))))))

(defn- attach-anchor! [link]
  (reset! *anchor-selector (element-selector link)))

(defn- detach-anchor! [_]
  (reset! *anchor-selector nil))

(defn- active-link []
  (let [{:keys [candidate-id container-id]}
        (:active (preview-state))
        exact (get @*link-elements [container-id candidate-id])]
    (or exact
        (some (fn [[_ {:keys [candidates-by-element]}]]
                (some (fn [[link data]]
                        (when (and (= (:url data)
                                      (get-in (preview-state) [:active :url]))
                                   (= (:origin-page data)
                                      (get-in (preview-state)
                                              [:active :origin-page])))
                          link))
                      candidates-by-element))
              @*containers))))

(defn- visited? [data]
  (contains? (:visited (preview-state)) (:url data)))

(defn- open! [data link]
  (clear-interaction-timer! :open)
  (clear-interaction-timer! :close)
  (when-not (visited? data)
    (detach-anchor! (active-link))
    (attach-anchor! link)
    (rf/dispatch [:link-preview/open data])))

(defn- close-preview! []
  (clear-interaction-timer! :open)
  (clear-interaction-timer! :close)
  (detach-anchor! (active-link))
  (rf/dispatch [:link-preview/close]))

(defn- schedule-close! []
  (clear-interaction-timer! :open)
  (when-not (#{:navigate :expanded}
              (get-in (preview-state) [:active :status]))
    (schedule! :close close-delay-ms close-preview!)))

(defn- unmodified-primary-click? [event]
  (and (zero? (.-button event))
       (not (.-altKey event))
       (not (.-ctrlKey event))
       (not (.-metaKey event))
       (not (.-shiftKey event))))

(defn- reduced-motion? []
  (.-matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))

(defn- active-candidate? [data]
  (let [active (:active (preview-state))]
    (= (select-keys data [:container-id :candidate-id])
       (select-keys active [:container-id :candidate-id]))))

(defn- container-handlers [id]
  (let [*touch-open-link (atom nil)
        link-data (fn [link]
                    (get-in @*containers [id :candidates-by-element link]))
        link-at (fn [event]
                  (let [root (get-in @*containers [id :element])
                        link (closest (.-target event) "a[href]")]
                    (when (and link root (.contains root link)
                               (external-link? link)
                               (link-data link))
                      link)))
        open-link! (fn [link]
                     (when-let [data (link-data link)]
                       (open! data link)))
        pointer-over
        (fn [event]
          (when-not (= "touch" (.-pointerType event))
            (when-let [link (link-at event)]
              (when-not (= link (closest (.-relatedTarget event) "a[href]"))
                (clear-interaction-timer! :close)
                (schedule! :open open-delay-ms
                           #(open-link! link))))))
        pointer-out
        (fn [event]
          (when-not (= "touch" (.-pointerType event))
            (when-let [link (link-at event)]
              (when-not (= link (closest (.-relatedTarget event) "a[href]"))
                (schedule-close!)))))
        focus-in
        (fn [event]
          (when-let [link (link-at event)]
            (clear-interaction-timer! :close)
            (schedule! :open open-delay-ms
                       #(open-link! link))))
        focus-out
        (fn [event]
          (when-let [link (link-at event)]
            (when-not (or (= link (closest (.-relatedTarget event) "a[href]"))
                          (closest (.-relatedTarget event) "[data-popover]"))
              (schedule-close!))))
        pointer-down
        (fn [event]
          (let [link (link-at event)]
            (reset! *touch-open-link
                    (when (and (= "touch" (.-pointerType event))
                               link
                               (not (visited? (link-data link)))
                               (not (active-candidate? (link-data link))))
                      link))))
        click
        (fn [event]
          (when-let [link (link-at event)]
            (let [data (link-data link)]
              (cond
                (= link @*touch-open-link)
                (do (.preventDefault event)
                    (.stopPropagation event)
                    (open! data link))

                (and (active-candidate? data)
                     (unmodified-primary-click? event))
                (do (.preventDefault event)
                    (rf/dispatch [:link-preview/status :navigate]))

                (unmodified-primary-click? event)
                (rf/dispatch-sync [:link-preview/visited (:url data)]))))
          (reset! *touch-open-link nil))]
    {"pointerover" pointer-over
     "pointerout" pointer-out
     "focusin" focus-in
     "focusout" focus-out
     "pointerdown" pointer-down
     "click" click}))

(defn register-container!
  "Register a candidate-bearing container. Returns its cleanup function."
  [id element options observer candidate-urls]
  (when (and element observer (seq candidate-urls))
    (let [handlers (container-handlers id)
          expected (set (mapcat util/href-variants candidate-urls))]
      ;; A real link container owns activation of the deferred global controller.
      ;; Loading User controls or rendering a plain page must not acquire it.
      (rf/dispatch [:loader/activate {:module :link-preview}])
      (swap! *containers assoc id
             {:element element
              :handlers handlers
              :observer observer
              :options options})
      (doseq [[event handler] handlers]
        (dom/on-event element event handler))
      (let [trust (trust-for element (:trust options))
            links (filter #(and (external-link? %)
                                (expected (.-href %))
                                (= element
                                   (closest % "[data-link-container]")))
                          (array-seq (.querySelectorAll element "a[href]")))
            candidates
            (mapv (fn [index link]
                    (let [data (candidate id index link trust)]
                      (swap! *link-elements assoc [id index] link)
                      (.observe observer link)
                      [link data]))
                  (range)
                  links)]
        (swap! *containers assoc-in [id :candidates-by-element]
               (into {} candidates))
        (rf/dispatch [:link-preview/register id (mapv second candidates)]))
      #(unregister-container! id))))

(defn unregister-container!
  "Disconnect and remove a link-owning container."
  [id]
  (when-let [{:keys [element handlers observer]} (get @*containers id)]
    ;; A queued hover callback must never reopen a detached element.
    (clear-interaction-timer! :open)
    (when (= id (get-in (preview-state) [:active :container-id]))
      (close-preview!))
    (.disconnect observer)
    (doseq [[event handler] handlers]
      (.removeEventListener element event handler))
    (swap! *containers dissoc id)
    (swap! *link-elements
           #(into {} (remove (fn [[[container-id _] _]]
                              (= id container-id)) %)))
    (rf/dispatch [:link-preview/unregister id])))

(defn- setup-link-feature [{:keys [id text] :as options}]
  (let [candidates @(rf/subscribe
                      [:link-preview/candidates
                       text
                       (.-href js/window.location)])]
    (when (and id (seq candidates))
      {:candidates candidates
       :id id
       :observer (candidate-observer id)
       :options options})))

(component/register-feature!
  :links
  {:transform (fn [form spec config]
                (if (and (or (:links spec) config) (motion/dom-root? form))
                  (let [attrs? (map? (second form))]
                    (with-meta
                      (into [(first form) (assoc (if attrs? (second form) {}) :data-link-container true)]
                            (if attrs? (nnext form) (next form)))
                      (meta form)))
                  form))
   :setup setup-link-feature
   :mount (fn [{:keys [candidates id observer options]} element]
            (register-container! id element options observer candidates))
   :unmount (fn [{:keys [id]}]
              (unregister-container! id))})

(defc ^:private <observed-link-container>
  [{:keys [candidates id trust]} content]
  (let [*element (atom nil)
        *cleanup (atom nil)
        observer (candidate-observer id)]
    (fn [_ content]
      (rf/use-effect
       (fn []
         (reset! *cleanup
                 (register-container! id @*element {:trust trust}
                                      observer candidates))
         #(when @*cleanup (@*cleanup))) #js [])
      [:div.link-preview-container
       {:data-link-container true
        :data-link-trust (when trust (name trust))
        :ref #(reset! *element %)}
       content])))

(defc <link-container>
  "Subscribe to raw text candidates and mount observation only when needed."
  [{:keys [id text trust]} content]
  (let [base-url (if (exists? js/window) (.-href js/window.location)
                      (str "https://tolgraven.se" (:path @context/*snapshot)))
        candidates @(rf/subscribe
                      [:link-preview/candidates text base-url])]
    (if (seq candidates)
      (with-meta
        [<observed-link-container>
         {:candidates candidates :id id :trust trust}
         content]
        {:key (hash [id trust text])})
      ;; Observation can differ by origin; the rendered attributes must still
      ;; match between SSR and the first browser render.
      [:div.link-preview-container
       {:data-link-container true :data-link-trust (when trust (name trust))}
       content])))

(defc <md>
  "Render markdown inside a candidate-aware preview container."
  [md & [options]]
  (let [id (or (:id options) (str "markdown-" (random-uuid)))]
    (fn [md & [options]]
      [<link-container>
       {:id id
        :text md
        :trust (:trust options)}
       [ui/<md->div> md options]])))

(defn article-dependency [url]
  {:source :url
   :url (str "/api/link-preview?url=" (js/encodeURIComponent url))
   :ttl-ms 3600000})

(defn frame-allowed? [result parent-url]
  (case (:frame-policy result)
    "none" true
    "same-origin" (= (.-origin (js/URL. (:url result)))
                     (.-origin (js/URL. parent-url)))
    false))

(defc <miniature> [{:keys [url trust]}]
  (let [*element (rf/use-ref nil)
        [scale set-scale!] (rf/use-state nil)]
    (rf/use-effect
     (fn []
       (let [measure! #(when-let [element (.-current *element)]
                        (set-scale! (/ (.-clientWidth element) (.-innerWidth js/window))))
             observer (when (exists? js/ResizeObserver)
                        (js/ResizeObserver. measure!))]
         (measure!)
         (when (and observer (.-current *element))
           (.observe observer (.-current *element)))
         (.addEventListener js/window "resize" measure!)
         #(do (when observer (.disconnect observer))
              (.removeEventListener js/window "resize" measure!)))) #js [])
    [:div.link-preview__miniature
     {:ref *element
      :aria-hidden true
      :style (when scale {"--preview-miniature-scale" scale})}
     [:div.link-preview__miniature-page
      [iframe/<iframe> {:label "Page miniature"
                        :src url
                        :trust trust}]]]))

(defc <readable-content> [result :- article/result candidate on-load]
  (let [[miniature? set-miniature!] (rf/use-state false)
        allowed? (and (exists? js/window)
                      (= "ready" (:status result))
                      (frame-allowed? result (.-href js/window.location)))]
    (rf/use-effect
     (fn []
       (on-load)
       ;; The article commits and paints before an optional page adds work.
       (let [*frame (atom nil)]
         (when allowed?
           (reset! *frame
                   (js/requestAnimationFrame
                    #(reset! *frame
                             (js/requestAnimationFrame
                              (fn [] (set-miniature! true)))))))
         #(when @*frame (js/cancelAnimationFrame @*frame))))
     #js [(:url result) allowed?])
    [:div.link-preview__readable
     {:class (when (empty? (:blocks result)) "link-preview__readable--metadata")}
     [:article.link-preview__article
      (when-let [image (:image result)]
        [:img.link-preview__image {:src image
                                  :alt ""
                                  :referrer-policy "no-referrer"
                                  :decoding "async"}])
      (when-not (string/blank? (:title result)) [:h2 (:title result)])
      (when-not (string/blank? (:description result))
        [:p.link-preview__description (:description result)])
      (map-indexed
       (fn [index {:keys [kind text level]}]
         (with-meta
           (case kind
             "heading" [(keyword (str "h" (min 4 (inc (or level 1))))) text]
             "quote" [:blockquote text]
             "code" [:pre [:code text]]
             "item" [:p.link-preview__item text]
             [:p text])
           {:key index}))
       (:blocks result))
      (when (and (= "ready" (:status result)) (empty? (:blocks result)))
        [:p.link-preview__more "Only page metadata is available here. Open the page to read its contents."])
      (when (:truncated? result)
        [:p.link-preview__more "Continue reading on the linked page…"])]
     (when (and allowed? miniature?)
       [<miniature> (assoc candidate :url (:url result))])]))

(defc <readable-preview>
  {:depends (fn [candidate _] [(article-dependency (:url candidate))])
   :loading-tag :div.link-preview__article
   :loading-prefab :text}
  [candidate on-load]
  (let [result (:value (data/snapshot (article-dependency (:url candidate))))]
    [<readable-content> result candidate on-load]))

(defc ^:private <default-preview> [candidate on-load]
  [<readable-preview> candidate on-load])

(defc ^:private <preview-content> [data on-load]
  (let [host (.-host (js/URL. (:url data)))
        renderer (get @*preview-providers host <default-preview>)]
    [renderer data on-load]))

(defn- save-transition! [data]
  (try
    (let [data (assoc data :view-state
                      {:scroll-x (.-scrollX js/window)
                       :scroll-y (.-scrollY js/window)})]
      (.setItem js/sessionStorage transition-storage-key
                (.stringify js/JSON (clj->js data))))
    (catch :default _ nil))) ; Storage restrictions must not prevent navigation.

(defn- stored-transition []
  (try
    (when-let [stored (.getItem js/sessionStorage transition-storage-key)]
      (let [data (js->clj (.parse js/JSON stored) :keywordize-keys true)]
        (when (= (:origin-page data) (page-key))
          (update data :trust #(cond-> % (string? %) keyword)))))
    (catch :default _ nil)))

(defn- restore-transition! []
  ;; Older departures stored a reverse-animation token. Consume only the visit;
  ;; the document/SPA restoration adapter owns scroll and layout restoration.
  (when-let [data (stored-transition)]
    (rf/dispatch-sync [:link-preview/visited (:url data)])
    data))

(defn- clear-transition! []
  (try
    (.removeItem js/sessionStorage transition-storage-key)
    (catch :default _ nil)))

(defc <link-preview>
  "Top-level renderer and transition/prefetch controller for link containers."
  []
  (let [state (rf/subscribe [:link-preview/state])
        *loaded-url (r/atom nil)
        *prefetch-timer (atom nil)
        *prefetches (r/atom #{})
        *navigation-timer (atom nil)
        dismiss! (fn []
                   (doseq [timer [*navigation-timer]]
                     (when @timer (js/clearTimeout @timer))
                     (reset! timer nil))
                   (clear-transition!)
                   (close-preview!))
        on-key-down (fn [event]
                      (when (and (= "Escape" (.-key event))
                                 (or (:active @state) (seq @*interaction-timers)))
                        (.preventDefault event)
                        (dismiss!)))
        on-pointer-down (fn [event]
                          (when (and (:active @state)
                                     (not (closest (.-target event) "[data-popover]"))
                                     (not= (active-link)
                                           (closest (.-target event) "a[href]")))
                            (dismiss!)))
        prefetch-next!
        (fn prefetch-next! []
          (if-let [{:keys [trust url]} (first (:prefetch-queue @state))]
            (do
              ;; React owns prefetch links and removes them on completion/unmount.
              (swap! *prefetches conj url)
              (rf/dispatch [:link-preview/prefetched url])
              (rf/dispatch [:link-preview/prefetch-next])
              (reset! *prefetch-timer
                      (js/setTimeout prefetch-next!
                                     (get util/prefetch-delay-ms trust))))
            (reset! *prefetch-timer nil)))
        maybe-prefetch! #(when (and (seq (:prefetch-queue @state))
                                    (not @*prefetch-timer))
                           (prefetch-next!))
        leave!
        (fn [data]
          (save-transition! data)
          (rf/dispatch-sync [:link-preview/visited (:url data)])
          (close-preview!)
          ;; Commit the closed surface before BFCache can freeze the document.
          (r/after-render #(do (local-page/departure!)
                               (.assign js/window.location (:url data)))))
        navigate!
        (fn [data]
          (clear-interaction-timer! :open)
          (clear-interaction-timer! :close)
          (when @*navigation-timer (js/clearTimeout @*navigation-timer))
          (rf/dispatch [:link-preview/status :expanded])
          (reset! *navigation-timer
                  (js/setTimeout #(leave! data)
                                 (if (reduced-motion?) 0 navigation-delay-ms))))
        restore!
        (fn []
          (when (restore-transition!) (close-preview!))
          (clear-transition!))
        on-page-show (fn [event]
                       (when (.-persisted event) (dismiss!))
                       (restore!))
        window-handlers {"pageshow" on-page-show
                         "keydown" on-key-down
                         "pointerdown" on-pointer-down}]
    (fn []
      (rf/use-effect
       (fn []
         (doseq [[event handler] window-handlers]
           (dom/on-window event handler))
         (restore!)
         #(do
            (doseq [[event handler] window-handlers]
              (.removeEventListener js/window event handler))
            (close-preview!)
            (doseq [timer [*prefetch-timer *navigation-timer]]
              (when @timer (js/clearTimeout @timer))))) #js [])
      (rf/use-effect
       (fn []
         (maybe-prefetch!)
         (when (= :navigate (get-in @state [:active :status]))
           (navigate! (:active @state)))
         js/undefined))
         (let [active (:active @state)
               {:keys [status title url]} active
               expanded? (= :expanded status)
               custom? (and url (contains? @*preview-providers (.-host (js/URL. url))))]
           [:<>
            (when (and (exists? js/document) @*anchor-selector)
              [portal/<portal> js/document.head
               [:style (str @*anchor-selector " { anchor-name: " popover/anchor-name "; }")]])
            (when (and (exists? js/document) (seq @*prefetches))
              [portal/<portal> js/document.head
               (into [:<>]
                     (for [url @*prefetches]
                       ^{:key url}
                       [:link {:rel "prefetch" :href url :as "document"
                               :referrer-policy "no-referrer"
                               :on-load #(swap! *prefetches disj url)
                               :on-error #(swap! *prefetches disj url)}]))])
            (when active
             [popover/<popover>
              {:aria-label (str "Preview of "
                                (if (string/blank? title) url title))
               :class "link-preview"
               :scaled? false
               :expanded? expanded?
               :on-click (fn [event]
                           (when (and (unmodified-primary-click? event)
                                      (string/blank? (str (.getSelection js/window)))
                                      (not (closest (.-target event)
                                                    "[data-popover-direct]")))
                             (.preventDefault event)
                             (navigate! active)))
               :on-key-down (fn [event]
                              (when (and (= (.-target event) (.-currentTarget event))
                                         (#{"Enter" " "} (.-key event)))
                                (.preventDefault event)
                                (navigate! active)))
               :on-focus #(clear-interaction-timer! :close)
               :on-blur #(when-not (some-> (.-currentTarget %)
                                          (.contains (.-relatedTarget %)))
                           (schedule-close!))
               :on-pointer-enter #(clear-interaction-timer! :close)
               :on-pointer-leave #(schedule-close!)
               :open? (boolean (active-link))}
              [:<>
               [:div.link-preview__bar
                [:a.link-preview__link
                 {:data-popover-direct true
                  :href url
                  :on-click (fn [event]
                              (when (unmodified-primary-click? event)
                                (.preventDefault event)
                                (navigate! active)))}
                 (if (string/blank? title) url title)]
                [:span.link-preview__hint "Click to open"]
                [:button.link-preview__close
                 {:aria-label "Close preview"
                  :data-popover-direct true
                  :on-click dismiss!}
                 "×"]]
               [:div.link-preview__viewport
                [<preview-content> active #(reset! *loaded-url url)]
                (when (and custom? (not= @*loaded-url url))
                  [:div.link-preview__loading
                   [:i.fa.fa-spinner.fa-spin]
                   [:span "Loading preview"]])]]])]))))
