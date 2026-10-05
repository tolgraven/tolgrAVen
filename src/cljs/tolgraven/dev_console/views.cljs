(ns tolgraven.dev-console.views
  (:require [cljs.reader :as reader]
            [clojure.string :as string]
            [reagent.core :as r]
            [re-frame.tooling :as tooling]
            [reitit.core :as reitit]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.component.registry :as registry]
            [tolgraven.component.persistent-state :as scoped]
            [tolgraven.component.restore :as restore]
            [tolgraven.component.data :as data]
            [tolgraven.dev-console.capture :as capture]
            [tolgraven.dev-console.state]
            [tolgraven.loader :as loader]
            [tolgraven.routes :as routes]))

(rf/reg-sub-raw :dev-console/catalog (fn [_ _] (rf/make-reaction #(deref registry/*catalog))))
(rf/reg-sub-raw :dev-console/resources (fn [_ _] (rf/make-reaction #(deref data/*entries))))
(rf/reg-sub-raw :dev-console/handlers
  (fn [_ _]
    (let [*handlers (r/atom @tooling/kind->id->handler)
          watch-id (random-uuid)]
      (add-watch tooling/kind->id->handler watch-id (fn [_ _ _ handlers] (reset! *handlers handlers)))
      (rf/make-reaction #(deref *handlers)
        :on-dispose #(remove-watch tooling/kind->id->handler watch-id)))))
(rf/reg-fx :dev-console/dispatch
  (fn [{:keys [event dry-run?]}]
    (try
      (let [event (reader/read-string event)]
        (when-not (and (vector? event) (keyword? (first event)))
          (throw (js/Error. "Enter an EDN event vector beginning with a keyword")))
        (if dry-run?
          (tooling/dispatch-with event
            (into {} (for [id [:http-xhrio :http/fetch :http/get :http/post :http/put :local-storage :navigate]]
                       [id (fn [value] (capture/emit! {:kind :stub :effect id :value value}))])))
          (-> (tooling/dispatch-and-settle event)
              (.then #(rf/dispatch [:dev-console/result (capture/settled-result %)]))
              (.catch #(rf/dispatch [:dev-console/result {:error (str %)}])))))
      (catch :default error (rf/dispatch [:dev-console/result {:error (str error)}])))))
(rf/reg-fx :dev-console/copy
  (fn [text]
    (-> (.writeText (.-clipboard js/navigator) text)
        (.catch #(rf/dispatch [:dev-console/result {:error (str %)}])))))
(rf/reg-event-fx :dev-console/copy (fn [_ [_ text]] {:dev-console/copy text}))
(rf/reg-event-fx :dev-console/dispatch (fn [_ [_ opts]] {:dev-console/dispatch opts}))
(rf/reg-event-db :dev-console/result (fn [db [_ value]] (assoc-in db [:dev-console :result] value)))

(defn value-type [value]
  (cond (nil? value) "nil" (boolean? value) "boolean" (number? value) "number"
        (keyword? value) "keyword" (string? value) "string" (map? value) "map"
        (vector? value) "vector" (set? value) "set" (sequential? value) "list"
        (fn? value) "function" :else "object"))
(defn parse-query [text]
  (try (let [query (reader/read-string text)]
         (when (and (vector? query) (keyword? (first query))) query))
       (catch :default _ nil)))
(defn entries [value]
  (if (map? value) (sort-by (comp pr-str key) value)
      (map-indexed vector value)))

(defn short-value
  ([value] (short-value value 0))
  ([value depth]
   (if (coll? value)
     (let [[open close] (cond (map? value) ["{" "}"] (set? value) ["#{" "}"]
                               (vector? value) ["[" "]"] :else ["(" ")"])]
       (if (< depth 2)
         (str open
              (string/join ", "
                (for [[key item] (take 2 (entries value))]
                  (str (when (map? value) (str (short-value key (inc depth)) " "))
                       (short-value item (inc depth)))))
              (when (seq (drop 2 (entries value))) " …") close)
         (str open close)))
     (cond (fn? value) "ƒ"
           (= "object" (value-type value)) "◌ object"
           :else (let [text (pr-str value)]
                   (if (> (count text) 42) (str (subs text 0 39) "…") text))))))
(defn key-width [value]
  (str (+ 3 (min 36 (reduce max 1 (map #(count (pr-str (first %))) (take 40 (entries value)))))) "ch"))

(defn inline-summary
  ([value] (inline-summary value 0 8))
  ([value depth limit]
   (let [kind (value-type value)
         [open close] (case kind "map" ["{" "}"] "set" ["#{" "}"]
                                "vector" ["[" "]"] ["(" ")"])
         items (when (< depth 2) (vec (take limit (entries value))))]
     [:span.dev-value__preview {:class (str "dev-value--" kind)}
      [:span.dev-value__delimiter open]
      (doall
        (for [[index [key item]] (map-indexed vector items)] ^{:key index}
          [:span.dev-value__preview-item
           (when (map? value)
             [:span.dev-value__key {:class (str "dev-value--" (value-type key))
                                   :title (pr-str key)} (str (short-value key) " ")])
           (if (coll? item)
             (inline-summary item (inc depth) 2)
             [:span {:class (str "dev-value--" (value-type item))
                     :title (short-value item)} (short-value item)])
           (when (< index (dec (count items))) [:span.dev-value__separator ", "])]))
      (when (and items (seq (drop limit (entries value)))) [:span.dev-value__separator " …"])
      [:span.dev-value__delimiter close]])))

(defc <value>
  {:state {:key (fn [path & _] (pr-str path)) :initial {:limit 40}}}
  [path value depth]
  :let [*expanded (<sub :comp [:expanded?] {:initial (< depth 2)})
        *limit (<sub :comp [:limit])]
  (let [kind (value-type value) collection? (coll? value)]
    [:div.dev-value {:class (str "dev-value--" kind)}
     (if collection?
       [:<>
        [:button.dev-value__summary {:on-click #(>update *expanded not)}
         [:span.dev-value__chevron (if @*expanded "▾" "▸")]
         (inline-summary value)
         [:span.dev-value__count (str " · " (count value))]]
        (when @*expanded
          [:div.dev-value__entries {:style {"--dev-key-width" (key-width value)}}
           [:svg.dev-value__enclosure {:viewBox "0 0 100 100" :preserveAspectRatio "none"
                                       :aria-hidden true :focusable false}
            (case kind
              ("map" "set") [:path {:d "M 5 1 C 75 1 20 43 92 50 C 20 57 75 99 5 99"
                                    :vector-effect "non-scaling-stroke"}]
              "vector" [:path {:d "M 1 9 L 1 1 L 99 1 L 99 9 M 1 91 L 1 99 L 99 99 L 99 91"
                               :vector-effect "non-scaling-stroke"}]
              [:path {:d "M 6 1 C 0 1 0 99 6 99 M 94 1 C 100 1 100 99 94 99"
                      :vector-effect "non-scaling-stroke"}])]

           (for [[key item] (take @*limit (entries value))]
             ^{:key (pr-str (conj path key))}
             [:div.dev-value__entry
              [:code.dev-value__key {:class (str "dev-value--" (value-type key)) :title (pr-str key)} (pr-str key)]
              [<value> (conj path key) item (inc depth)]])
           (when (> (count value) @*limit)
             [:button {:on-click #(>update *limit + 40)} "Show next 40"])] )]
       [:code {:title (pr-str path)}
        (case kind "function" "ƒ function" "nil" "nil"
              "string" (if (re-matches #"https?://[^\s]+" value)
                         [:a {:href value :target "_blank" :rel "noopener noreferrer"} value]
                         (pr-str value))
              (pr-str value))])]))

(defc <query-result> [query]
  (if (contains? (get @(rf/subscribe [:dev-console/handlers]) :sub) (first query))
    [<value> [:query query] @(rf/subscribe query) 0]
    [:p "No registered subscription with this ID."]))

(defn trace-depth [index trace]
  (loop [id (:child-of trace) depth 0 seen #{}]
    (if (or (nil? id) (seen id) (nil? (get index id))) depth
        (recur (:child-of (get index id)) (inc depth) (conj seen id)))))
(defn timing-lanes [records]
  (let [index (into {} (map (juxt :id identity)) (filter #(= :trace (:kind %)) records))]
    (->> records
         (group-by #(if (= :trace (:kind %)) [:trace (trace-depth index %)] [(:kind %) 0]))
         (sort-by key))))

(defc <flamegraph>
  {:state {:initial {:epoch nil :selected nil}}}
  [records]
  :let [*epoch (<sub :comp [:epoch]) *selected (<sub :comp [:selected])]
  (let [epochs (vec (take-last 30 (filter #(= :epoch (:kind %)) records)))
        epoch (or (some #(when (= @*epoch (str (:dispatch-id %))) %) epochs) (last epochs))
        timings (filter #(and (number? (:start %)) (number? (:end %)) (number? (:duration %))) records)
        ;; Show one event and its following commits, rather than compressing minutes
        ;; of unrelated activity into bars too small to inspect.
        start (or (:start epoch) (some-> timings last :start))
        end (when start (+ (or (:end epoch) start) 250))
        timings (vec (take-last 100 (filter #(and (not= :epoch (:kind %)) start
                                                 (<= start (:start %) end)) timings)))
        span (when start (max 1 (- end start)))]
    [:div
     [:label "Event window "
      [:select {:value (str (:dispatch-id epoch))
                :on-change #(>reset *epoch (.. % -target -value))}
       (for [epoch epochs] ^{:key (:dispatch-id epoch)}
         [:option {:value (str (:dispatch-id epoch))}
          (str (:dispatch-id epoch) " · " (pr-str (:event epoch)))])]]
     [:p "Nested re-frame traces and React commit durations, followed by 250 ms of rendering. Click a bar to inspect it. Profiler durations include child work."]
     [:div.dev-flamegraph
      (if (empty? timings) [:p "No timing records in this window. Navigate or interact with a component."]
        (for [[lane traces] (timing-lanes timings)] ^{:key (pr-str lane)}
          [:div.dev-flamegraph__row
           [:span.dev-flamegraph__label (str (name (first lane)) " " (second lane))]
           [:div.dev-flamegraph__track
            (for [[i trace] (map-indexed vector traces)] ^{:key i}
              [:button.dev-flamegraph__bar
               {:on-click #(>reset *selected trace)
                :style {:left (str (* 100 (/ (- (:start trace) start) span)) "%")
                        :width (str (max 0.5 (* 100 (/ (:duration trace) span))) "%")}
                :title (str (or (:operation trace) (:component trace)) " · " (:duration trace) "ms")}
               (str (or (:operation trace) (:component trace)) " " (.toFixed (:duration trace) 2) "ms")])]]))]
     (when @*selected [<value> [:selected-timing] @*selected 0])]))

(defc <path-inspector> [path-text value-text]
  (let [path (try (reader/read-string @path-text) (catch :default _ nil))]
    [:div
     [:label "App-db path (EDN) " [:input {:value @path-text :on-change #(scoped/>reset path-text (.. % -target -value))}]]
     (if (vector? path)
       [:<>
        [:code "Subscription: " (pr-str [:dev-console/path path])]
        [<value> [:path path] @(rf/subscribe [:dev-console/path path]) 0]
        [:label "New value (EDN) " [:input {:value @value-text :on-change #(scoped/>reset value-text (.. % -target -value))}]]
        [:button {:on-click #(try (scoped/>reset (with-meta path {:component-path true :component-root path})
                                                (reader/read-string @value-text))
                                 (catch :default error (rf/dispatch [:dev-console/result {:error (str error)}])))} "Set through state event"]]
       [:p "Enter a vector path."])]))

(defc <console>
  {:state {:initial {:open? false :tab :page :query "[:common/route]" :path "[:state]"
                     :value "nil" :event "[:page/preload-links]" :dry-run? false}}}
  []
  :let [*open (<sub :comp [:open?]) *tab (<sub :comp [:tab])
        *query (<sub :comp [:query]) *path (<sub :comp [:path]) *value (<sub :comp [:value])
        *event (<sub :comp [:event]) *dry-run (<sub :comp [:dry-run?])]
  (let [options @(rf/subscribe [:dev-console/options])
        debug @(rf/subscribe [:dev-console/data])
        snapshot @(rf/subscribe [:dev-console/snapshot])
        route @(rf/subscribe [:common/route])
        tab-value @*tab
        records (:records debug)]
    ;; Layout effect enables the consumer before child probe mount effects run.
    (rf/use-layout-effect (fn [] (capture/connect!)) #js [])
    (rf/use-effect
      (fn [] (if (:recording? options) (capture/start!) js/undefined))
      #js [(:recording? options)])
    (rf/use-effect
      (fn []
        (let [handler (fn [event]
                        (when (and (= "D" (string/upper-case (.-key event)))
                                   (.-altKey event) (.-shiftKey event))
                          (.preventDefault event) (scoped/>update *open not)))]
          (.addEventListener js/window "keydown" handler)
          #(.removeEventListener js/window "keydown" handler))) #js [])
    [:aside.dev-console {:aria-label "Development console" :data-dev-console true}
     [:button.dev-console__toggle {:on-click #(>update *open not) :aria-expanded (boolean @*open)} "Dev " (if @*open "×" "⌘")]
     (when @*open
       [:section.dev-console__panel {:aria-label "Re-frame inspector"}
        [:div.dev-console__header
         [:strong "tolgrAVen · re-frame inspector"]
         [:span (str (count (:active debug)) " active components · " (count records) " records")]
         [:label [:input {:type "checkbox" :checked (boolean (:recording? options))
                          :on-change #(rf/dispatch [:dev-console/option :recording? (.. % -target -checked)])}] "Record"]
         [:label [:input {:type "checkbox" :checked (boolean (:hydration-highlight? options))
                          :on-change #(rf/dispatch [:dev-console/option :hydration-highlight? (.. % -target -checked)])}] "Hydration flash"]
         [:button {:on-click #(rf/dispatch [:dev-console/clear])} "Clear"]
         [:button {:on-click #(rf/dispatch [:dev-console/copy (pr-str snapshot)])} "Copy snapshot"]]
        [:div.dev-console__tabs
         (for [tab [:page :components :modules :state :subs :loads :events :errors :timings :layout :code]]
           ^{:key tab} [:button {:class (when (= tab tab-value) "active") :on-click #(>reset *tab tab)} (name tab)])]
        [:div.dev-console__body
         (case @*tab
           :page [<value> [:page] {:route route :spec (:data route)
                                 :page-state @(rf/subscribe [:dev-console/path [:page (restore/page-key)]])} 0]
           :components (into [:div] (for [[id component] (sort-by (comp pr-str key) (:active debug))]
                               ^{:key id}
                               [:details [:summary (str (string/join "/" (:component component)) " · " (:key component))]
                                [:button {:on-click #(do (>reset *path (pr-str (:path component))) (>reset *tab :state))} "Inspect state path"]
                                [<value> [:component id] component 0]
                                [<value> [:component-state id] @(rf/subscribe [:dev-console/path (:path component)]) 0]]))
           :modules [<value> [:modules]
                     {:pages (reitit/routes routes/router)
                      :modules (into {} (for [[id loadable] loader/modules]
                                          [id (if (loader/ready? id) @loadable :code-not-loaded)]))
                      :module-state @(rf/subscribe [:dev-console/path [:module]])} 0]
           :state [:<> [<path-inspector> *path *value] [<value> [:app-db] @(rf/subscribe [:dev-console/db]) 0]]
           :subs [:<>
                  [:label "Subscription query " [:input {:value @*query :on-change #(>reset *query (.. % -target -value))}]]
                  (when-let [query (parse-query @*query)] ^{:key (pr-str query)} [<query-result> query])
                  [<value> [:live-subs] (tooling/live-query-vs) 0]]
           :loads [<value> [:loads] {:resources @(rf/subscribe [:dev-console/resources])
                                    :loading @(rf/subscribe [:loading])
                                    :store @(rf/subscribe [:dev-console/path [:store]])} 0]
           :events [:<>
                    [:label "Event vector " [:input {:value @*event :on-change #(>reset *event (.. % -target -value))}]]
                    [:label [:input {:type "checkbox" :checked (boolean @*dry-run) :on-change #(>reset *dry-run (.. % -target -checked))}]
                     "Stub common network/navigation/storage effects (other effects still run)"]
                    [:button {:on-click #(rf/dispatch [:dev-console/dispatch {:event @*event :dry-run? @*dry-run}])} "Dispatch and trace"]
                    [<value> [:dispatch-result] (:result debug) 0]
                    [<value> [:epochs] (filterv #(= :epoch (:kind %)) records) 0]]
           :errors [<value> [:errors] @(rf/subscribe [:dev-console/path [:diagnostics]]) 0]
           :timings [:<> [<flamegraph> records] [<value> [:timings] records 0]]
           :layout [<value> [:layout] {:shifts (filterv #(= :layout (:kind %)) records)
                                      :renders (filterv #(= :render (:kind %)) records)
                                      :supported? (and (exists? js/PerformanceObserver)
                                                       (some #{"layout-shift"} (array-seq (.-supportedEntryTypes js/PerformanceObserver))))} 0]
           :code [<value> [:code] {:components @(rf/subscribe [:dev-console/catalog])
                                   :registrations (into {} (for [[kind handlers] @(rf/subscribe [:dev-console/handlers])]
                                                            [kind (into {} (map (fn [[id handler]] [id (meta handler)]) handlers))]))} 0])]])]))
