(ns tolgraven.components.link-preview
  (:require
    [clojure.string :as string]
    [goog.string :as gstring]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [tolgraven.components.iframe :as iframe]
    [tolgraven.components.popover :as popover]
    [tolgraven.routes :as routes]))

(def ^:private open-delay-ms 300)
(def ^:private close-delay-ms 180)
(def ^:private navigation-delay-ms 420)
(def ^:private return-close-delay-ms 500)
(def ^:private transition-storage-key "tolgraven.link-preview.transition")
(def ^:private prefetch-delay-ms {:trusted 250, :user 1500})

(defonce *containers (atom {}))
(defonce *link-elements (atom {}))
(defonce *preview-providers (atom {}))
(defonce *interaction-timers (atom {}))

(declare unregister-container!)

(defn- trim-url-token [url]
  (loop [url (string/replace url #"[.,;:!?]+$" "")]
    (let [closer (last url)
          opener ({\) \( \] \[ \} \{} closer)]
      (if (and opener
               (> (count (filter #{closer} url))
                  (count (filter #{opener} url))))
        (recur (subs url 0 (dec (count url))))
        url))))

(defn- href-variants [url]
  [url (try
         (.-href (js/URL. (gstring/unescapeEntities url)))
         (catch :default _ url))])

(defn external-urls
  "Extract distinct external HTTP(S) URLs from raw markdown, HTML, or text."
  [text base-url]
  (->> (re-seq #"(?:https?:)?//[^\s<>\"']+" (or text ""))
       (map trim-url-token)
       (keep #(try
                (.-href (js/URL. % base-url))
                (catch :default _ nil)))
       (filter #(routes/external-http-url? % base-url))
       distinct
       vec))

(rf/reg-sub
  :link-preview/raw-text
  (fn [_ [_ text]]
    (or text "")))

(rf/reg-sub
  :link-preview/candidates
  (fn [[_ text]]
    [(rf/subscribe [:link-preview/raw-text text])])
  (fn [[text] [_ _ base-url]]
    (external-urls text base-url)))

(rf/reg-sub
  :link-preview/state
  (fn [db _]
    (get-in db [:state :link-preview]
            {:containers {}
             :prefetch-queue []})))

(rf/reg-sub
  :link-preview/containers
  :<- [:link-preview/state]
  (fn [state _]
    (:containers state)))

(rf/reg-sub
  :link-preview/link-count
  :<- [:link-preview/containers]
  (fn [containers _]
    (reduce + (map :count (vals containers)))))

(rf/reg-event-db
  :link-preview/register
  (fn [db [_ id candidates]]
    (assoc-in db [:state :link-preview :containers id]
              {:candidates candidates
               :count (count candidates)})))

(rf/reg-event-db
  :link-preview/unregister
  (fn [db [_ id]]
    (let [state (get-in db [:state :link-preview])
          active (:active state)]
      (assoc-in db [:state :link-preview]
                (cond-> (-> state
                            (update :containers dissoc id)
                            (update :prefetch-queue
                                    #(vec (remove (fn [candidate]
                                                   (= id (:container-id candidate)))
                                                 %))))
                  (= id (:container-id active)) (dissoc :active))))))

(rf/reg-event-db
  :link-preview/visible
  (fn [db [_ candidate]]
    (let [path [:state :link-preview]
          url (:url candidate)
          trust (:trust candidate)
          queued? (get-in db (conj path :prefetch url))]
      (if (or queued? (not (get prefetch-delay-ms trust)))
        db
        (-> db
            (assoc-in (conj path :prefetch url) :queued)
            (update-in (conj path :prefetch-queue) (fnil conj []) candidate))))))

(rf/reg-event-db
  :link-preview/prefetch-next
  (fn [db _]
    (update-in db [:state :link-preview :prefetch-queue]
               #(vec (rest %)))))

(rf/reg-event-db
  :link-preview/prefetched
  (fn [db [_ url]]
    (assoc-in db [:state :link-preview :prefetch url] :prefetched)))

(rf/reg-event-db
  :link-preview/open
  (fn [db [_ candidate]]
    (assoc-in db [:state :link-preview :active]
              (assoc candidate :status :preview))))

(rf/reg-event-db
  :link-preview/status
  (fn [db [_ status]]
    (assoc-in db [:state :link-preview :active :status] status)))

(rf/reg-event-db
  :link-preview/restore
  (fn [db [_ transition]]
    (assoc-in db [:state :link-preview :active]
              (assoc transition :status :returning))))

(rf/reg-event-db
  :link-preview/close
  (fn [db _]
    (update-in db [:state :link-preview] dissoc :active)))

(defonce *preview-state (rf/subscribe [:link-preview/state]))

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
       (routes/external-http-url? (.-href link)
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
    #js {:rootMargin "25%"}))

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

(defn- attach-anchor! [link]
  (.setProperty (.-style link) "anchor-name" popover/anchor-name))

(defn- detach-anchor! [link]
  (when link
    (.removeProperty (.-style link) "anchor-name")))

(defn- active-link []
  (let [{:keys [candidate-id container-id]}
        (:active @*preview-state)
        exact (get @*link-elements [container-id candidate-id])]
    (or exact
        (some (fn [[_ {:keys [candidates-by-element]}]]
                (some (fn [[link data]]
                        (when (and (= (:url data)
                                      (get-in @*preview-state [:active :url]))
                                   (= (:origin-page data)
                                      (get-in @*preview-state
                                              [:active :origin-page])))
                          link))
                      candidates-by-element))
              @*containers))))

(defn- open! [data link]
  (clear-interaction-timer! :close)
  (detach-anchor! (active-link))
  (attach-anchor! link)
  (rf/dispatch [:link-preview/open data]))

(defn- schedule-close! []
  (schedule! :close close-delay-ms
             #(do (detach-anchor! (active-link))
                  (rf/dispatch [:link-preview/close]))))

(defn- unmodified-primary-click? [event]
  (and (zero? (.-button event))
       (not (.-altKey event))
       (not (.-ctrlKey event))
       (not (.-metaKey event))
       (not (.-shiftKey event))))

(defn- reduced-motion? []
  (.matches (.matchMedia js/window "(prefers-reduced-motion: reduce)")))

(defn- active-candidate? [data]
  (let [active (:active @*preview-state)]
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
            (schedule! :open open-delay-ms
                       #(open-link! link))))
        focus-out
        (fn [event]
          (when-let [link (link-at event)]
            (when-not (= link (closest (.-relatedTarget event) "a[href]"))
              (schedule-close!))))
        pointer-down
        (fn [event]
          (let [link (link-at event)]
            (reset! *touch-open-link
                    (when (and (= "touch" (.-pointerType event))
                               link
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
                    (rf/dispatch [:link-preview/status :navigate])))))
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
          expected (set (mapcat href-variants candidate-urls))]
      (swap! *containers assoc id
             {:element element
              :handlers handlers
              :observer observer
              :options options})
      (.setAttribute element "data-link-container" (str id))
      (doseq [[event handler] handlers]
        (.addEventListener element event handler))
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
    (.disconnect observer)
    (doseq [[event handler] handlers]
      (.removeEventListener element event handler))
    (.removeAttribute element "data-link-container")
    (swap! *containers dissoc id)
    (swap! *link-elements
           #(into {} (remove (fn [[[container-id _] _]]
                              (= id container-id)) %)))
    (rf/dispatch [:link-preview/unregister id])))

(defn- <observed-link-container>
  [{:keys [candidates id trust]} content]
  (let [*element (atom nil)
        *cleanup (atom nil)
        observer (candidate-observer id)]
    (r/create-class
      {:display-name "Observed link container"
       :component-did-mount
       (fn [_]
         (reset! *cleanup
                 (register-container! id @*element {:trust trust}
                                      observer candidates)))
       :component-will-unmount
       (fn [_]
         (when @*cleanup (@*cleanup)))
       :reagent-render
       (fn [_ content]
         [:div.link-preview-container
          {:data-link-trust (when trust (name trust))
           :ref #(reset! *element %)}
          content])})))

(defn <link-container>
  "Subscribe to raw text candidates and mount observation only when needed."
  [{:keys [id text trust]} content]
  (let [base-url (.-href js/window.location)
        candidates @(rf/subscribe
                      [:link-preview/candidates text base-url])]
    (if (seq candidates)
      (with-meta
        [<observed-link-container>
         {:candidates candidates :id id :trust trust}
         content]
        {:key (hash [id trust candidates])})
      [:div.link-preview-container content])))

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

(defn- save-transition! [data]
  (let [data (assoc data :view-state
                    {:scroll-x (.-scrollX js/window)
                     :scroll-y (.-scrollY js/window)})]
    (.setItem js/sessionStorage transition-storage-key
              (.stringify js/JSON (clj->js data)))))

(defn- stored-transition []
  (when-let [stored (.getItem js/sessionStorage transition-storage-key)]
    (try
      (let [data (js->clj (.parse js/JSON stored) :keywordize-keys true)]
        (when (= (:origin-page data) (page-key))
          (update data :trust #(cond-> % (string? %) keyword))))
      (catch :default _ nil))))

(defn- restore-transition! []
  (when-let [data (stored-transition)]
    (when-let [{:keys [scroll-x scroll-y]} (:view-state data)]
      (.scrollTo js/window scroll-x scroll-y))
    (rf/dispatch-sync [:link-preview/restore data])
    data))

(defn- clear-transition! []
  (.removeItem js/sessionStorage transition-storage-key))

(defn <link-preview>
  "Top-level renderer and transition/prefetch controller for link containers."
  []
  (let [state *preview-state
        *initial-transition (r/atom
                              (some-> (stored-transition)
                                      (assoc :status :returning)))
        *loaded-url (r/atom nil)
        *prefetch-timer (atom nil)
        *navigation-timer (atom nil)
        *restore-timer (atom nil)
        *reversing? (atom false)
        prefetch-next!
        (fn prefetch-next! []
          (if-let [{:keys [trust url]} (first (:prefetch-queue @state))]
            (let [link (.createElement js/document "link")]
              (set! (.-rel link) "prefetch")
              (set! (.-href link) url)
              (set! (.-onload link) #(.remove link))
              (set! (.-onerror link) #(.remove link))
              (.setAttribute link "as" "document")
              (.appendChild js/document.head link)
              (rf/dispatch [:link-preview/prefetched url])
              (rf/dispatch [:link-preview/prefetch-next])
              (reset! *prefetch-timer
                      (js/setTimeout prefetch-next!
                                     (get prefetch-delay-ms trust))))
            (reset! *prefetch-timer nil)))
        maybe-prefetch! #(when (and (seq (:prefetch-queue @state))
                                    (not @*prefetch-timer))
                           (prefetch-next!))
        navigate!
        (fn [data]
          (save-transition! data)
          (rf/dispatch [:link-preview/status :expanded])
          (reset! *navigation-timer
                  (js/setTimeout
                    #(.assign js/window.location (:url data))
                    (if (reduced-motion?) 0 navigation-delay-ms))))
        reverse!
        (fn []
          (when (= :returning (get-in @state [:active :status]))
            (when-let [link (active-link)]
              (when @*restore-timer
                (js/clearTimeout @*restore-timer)
                (reset! *restore-timer nil))
              (attach-anchor! link)
              (reset! *reversing? true)
              (js/requestAnimationFrame
                (fn []
                  (js/requestAnimationFrame
                    (fn []
                      (rf/dispatch [:link-preview/status :preview])
                      (js/setTimeout
                        #(do
                           (reset! *reversing? false)
                           (clear-transition!)
                           (when-not (or (.matches link ":hover")
                                         (some-> js/document
                                                 (.querySelector "[data-popover]:hover")))
                             (schedule-close!)))
                        return-close-delay-ms))))))))
        restore!
        (fn []
          (when (restore-transition!)
            (reset! *initial-transition nil))
          (when @*restore-timer
            (js/clearTimeout @*restore-timer))
          (reset! *restore-timer
                  (js/setTimeout
                    #(when (= :returning
                              (get-in @state [:active :status]))
                       (clear-transition!)
                       (rf/dispatch [:link-preview/close]))
                    (* 3 return-close-delay-ms))))
        on-page-show (fn [_] (restore!))]
    (r/create-class
      {:display-name "Link preview controller"
       :component-did-mount
       (fn [_]
         (.addEventListener js/window "pageshow" on-page-show)
         (restore!)
         (maybe-prefetch!))
       :component-did-update
       (fn [_ _]
         (maybe-prefetch!)
         (when (= :navigate (get-in @state [:active :status]))
           (navigate! (:active @state)))
         (reverse!))
       :component-will-unmount
       (fn [_]
         (.removeEventListener js/window "pageshow" on-page-show)
         (doseq [timer [*prefetch-timer *navigation-timer *restore-timer]]
           (when @timer (js/clearTimeout @timer))))
       :reagent-render
       (fn []
         (let [active (or (:active @state) @*initial-transition)
               {:keys [status title url]} active
               expanded? (#{:expanded :returning} status)]
           (when active
             [popover/<popover>
              {:aria-label (str "Preview of "
                                (if (string/blank? title) url title))
               :class "link-preview"
               :expanded? expanded?
               :on-click (fn [event]
                           (when (and (unmodified-primary-click? event)
                                      (not (closest (.-target event)
                                                    "[data-popover-direct]")))
                             (.preventDefault event)
                             (navigate! active)))
               :on-pointer-enter #(clear-interaction-timer! :close)
               :on-pointer-leave #(when-not @*reversing?
                                    (schedule-close!))
               :open? (or (= :returning status)
                          (boolean (active-link)))}
              [:<>
               [:div.link-preview__bar
                [:a.link-preview__link
                 {:data-popover-direct true
                  :href url}
                 (if (string/blank? title) url title)]
                [:span.link-preview__hint "Click to open"]]
               [:div.link-preview__viewport
                [<preview-content> active #(reset! *loaded-url url)]
                (when-not (= @*loaded-url url)
                  [:div.link-preview__loading
                   [:i.fa.fa-spinner.fa-spin]
                   [:span "Loading preview"]])
                [:div.link-preview__shield {:aria-hidden true}]]]])))})))
