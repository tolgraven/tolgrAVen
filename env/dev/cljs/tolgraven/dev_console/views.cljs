(ns tolgraven.dev-console.views
  (:require [tolgraven.components.validation :as validation] [cljs.reader :as reader]
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
            [tolgraven.dev-console.layout :as layout]
            [tolgraven.dev-console.components :as components]
            [tolgraven.dev-console.state]
            [tolgraven.dev.source-links :as source]
            [tolgraven.loader :as loader]
            [tolgraven.navigation.routes :as routes]))

(rf/reg-sub-raw :dev-console/component-parents (fn [_ _] (rf/make-reaction #(deref capture/*component-parents))))
(rf/reg-sub-raw :dev-console/catalog (fn [_ _] (rf/make-reaction #(deref registry/*catalog))))
(rf/reg-sub-raw :dev-console/resources (fn [_ _] (rf/make-reaction #(deref data/*entries))))
(rf/reg-sub-raw :dev-console/handlers
  (fn [_ _]
    (let [*handlers (r/atom @tooling/kind->id->handler)
          watch-id (random-uuid)]
      (add-watch tooling/kind->id->handler watch-id (fn [_ _ _ handlers] (reset! *handlers handlers)))
      (rf/make-reaction #(deref *handlers)
        :on-dispose #(remove-watch tooling/kind->id->handler watch-id)))))
(rf/reg-sub-raw :dev-console/dependency-status
  (fn [_ [_ resource]]
    (rf/make-reaction #(or (:status (data/snapshot resource)) :unrequested))))

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
  (if (map? value) (seq value)
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

(defn duration-label [milliseconds]
  (let [seconds? (>= (js/Math.abs milliseconds) 1000)
        value (if seconds? (/ milliseconds 1000) milliseconds)
        rounded (/ (js/Math.round (* value 1000)) 1000)]
    (str rounded (if seconds? " s" " ms"))))

(defn elapsed-label [milliseconds]
  (let [total (max 0 (js/Math.round milliseconds))
        hours (quot total 3600000)
        minutes (mod (quot total 60000) 60)
        seconds (mod (quot total 1000) 60)
        ms (mod total 1000)]
    (str hours "h " minutes "m " seconds "s " ms "ms after load")))

(defn timing-value [path value]
  (when (number? value)
    (let [key (last path)]
      (cond
        (#{:duration :base-duration :since-input} key)
        [:span {:title (str value " milliseconds (raw)")} (duration-label value)]
        (and (#{:start :end :commit :last-input} key) (js/Number.isFinite value))
        (let [origin (when (exists? js/performance) (.-timeOrigin js/performance))
              timestamp (when (number? origin) (.toISOString (js/Date. (+ origin value))))]
          [:span.dev-time {:title (str value " milliseconds after performance.timeOrigin (raw)")}
           [:span (elapsed-label value)]
           (when timestamp " · ")
           (when timestamp [:time {:date-time timestamp} timestamp])])))))

(defn map-vector? [value]
  (and (vector? value) (seq value) (every? map? value)))

(defn inline-summary
  ([value] (inline-summary value 0 8))
  ([value depth limit]
   (let [kind (value-type value)
         [open close] (case kind "map" ["{" "}"] "set" ["#{" "}"]
                                "vector" ["[" "]"] ["(" ")"])
         map-vector? (map-vector? value)
         items (when (and (not map-vector?) (< depth 2)) (vec (take limit (entries value))))]
     [:span.dev-value__preview {:class (str "dev-value--" kind)}
      [:span.dev-value__delimiter open]
      (when map-vector?
        [:<>
         [:span.dev-value__preview {:class "dev-value--map"}
          [:span.dev-value__delimiter "{}"]]
         [:span.dev-value__count (str " × " (count value))]])
      (doall
        (for [[index [key item]] (map-indexed vector items)] ^{:key index}
          [:span.dev-value__preview-item
           (when (map? value)
             [:span.dev-value__key {:class (str "dev-value--" (value-type key))
                                   :title (pr-str key)} (str (short-value key) " ")])
           (if (coll? item)
             (inline-summary item (inc depth) 2)
             [:span {:class (str "dev-value--" (value-type item))
                     :title (short-value item)} (or (when (map? value) (timing-value [key] item)) (short-value item))])
           (when (< index (dec (count items))) [:span.dev-value__separator ", "])]))
      (when (and items (seq (drop limit (entries value)))) [:span.dev-value__separator " …"])
      [:span.dev-value__delimiter close]])))

(defn inline-complete?
  "Use exactly the preview's depth and item budget; omitted values still expand."
  ([value] (inline-complete? value 0 8))
  ([value depth limit]
   (if (coll? value)
     (and (not (map-vector? value))
          (or (empty? value) (< depth 2))
          (<= (bounded-count (inc limit) value) limit)
          (every? (fn [[key item]]
                    (and (or (not (map? value)) (inline-complete? key (inc depth) 2))
                         (inline-complete? item (inc depth) 2)))
                  (take limit (entries value))))
     (and (not (fn? value)) (not= "object" (value-type value))
          (<= (count (pr-str value)) 42)))))

(defc <value>
  {:state {:key (fn [path & _] (pr-str path)) :initial {:page 0}}}
  [path value depth]
  :let [*expanded (<sub :comp [:expanded?] {:initial (and (< depth 2) (or (zero? depth) (and (coll? value) (<= (bounded-count 13 value) 12))))})
        *page (<sub :comp [:page])]
  (let [kind (value-type value) collection? (coll? value)
        page (when collection? (layout/page (entries value) (or @*page 0) (when (counted? value) (count value))))
        total (when collection? (when (counted? value) (count value)))
        expandable? (not (inline-complete? value))]
    [:div.dev-value {:class (str "dev-value--" kind)}
     (if collection?
       [:<>
        [(if expandable? :button.dev-value__summary :div.dev-value__summary)
         (if expandable? {:on-click #(>update *expanded not) :aria-expanded (boolean @*expanded)} {})
         (when expandable? [:span.dev-value__chevron (if @*expanded "▾" "▸")])
         (inline-summary value)
         [:span.dev-value__count (str " · " (or total "…"))]]
        (when (and expandable? @*expanded)
          [:div.dev-value__entries {:style {"--dev-key-width" (key-width (into {} (:items page)))}}
           [:svg.dev-value__enclosure {:viewBox "0 0 100 100" :preserveAspectRatio "none"
                                       :aria-hidden true :focusable false}
            (case kind
              ("map" "set") [:path {:d "M 5 1 C 75 1 20 43 92 50 C 20 57 75 99 5 99"
                                    :vector-effect "non-scaling-stroke"}]
              "vector" [:path {:d "M 1 9 L 1 1 L 99 1 L 99 9 M 1 91 L 1 99 L 99 99 L 99 91"
                               :vector-effect "non-scaling-stroke"}]
              [:path {:d "M 6 1 C 0 1 0 99 6 99 M 94 1 C 100 1 100 99 94 99"
                      :vector-effect "non-scaling-stroke"}])]

           (for [[key item] (:items page)]
             ^{:key (pr-str (conj path key))}
             [:div.dev-value__entry
              [:code.dev-value__key {:class (str "dev-value--" (value-type key)) :title (pr-str key)} (pr-str key)]
              [<value> (conj path key) item (inc depth)]])
           (when (or (pos? (:index page)) (:more? page))
             [:div.dev-value__pagination
              [:button {:disabled (zero? (:index page)) :on-click #(>reset *page (dec (:index page)))} "Previous 10"]
              [:span (str (inc (:offset page)) "–" (+ (:offset page) (count (:items page)))
                          (when total (str " of " total)))]
              [:button {:disabled (not (:more? page)) :on-click #(>reset *page (inc (:index page)))} "Next 10"]])] )]
       [:code {:title (pr-str path)}
        (or (timing-value path value)
            (case kind "function" "ƒ function" "nil" "nil"
              "string" (if (re-matches #"https?://[^\s]+" value)
                         [:a {:href value :target "_blank" :rel "noopener noreferrer"} value]
                         (pr-str value))
              (pr-str value)))])]))

(defc <query-result>
  {:features [:error-boundary]}
  [query]
  (try
    (if (contains? (get @(rf/subscribe [:dev-console/handlers]) :sub) (first query))
      (let [value @(rf/subscribe query)]
        [:section {:aria-label "Subscription result"}
         [:h4 "Result · " (pr-str query)]
         (when (nil? value) [:p "The subscription returned nil. Check its arguments or loading status."])
         [<value> [:query query] value 0]])
      [:p {:role "alert"} "No registered subscription with this ID. Load its module or select a registered query."])
    (catch :default error
      [:p {:role "alert"} "Subscription failed: " (or (ex-message error) (str error))])))

(defn vector-matches? [text query]
  (let [needle (string/lower-case (string/trim text))
        parsed (parse-query text)]
    (cond
      parsed (= parsed (vec (take (count parsed) query)))
      (string/starts-with? needle "[") (string/starts-with? (string/lower-case (pr-str query)) needle)
      :else (string/includes? (string/lower-case (str (first query))) needle))))

(defn vector-suggestions [text ids live]
  (->> (concat live (map vector ids))
       distinct (filter #(vector-matches? text %)) (sort-by pr-str) (take 10) vec))

(defn query-suggestions [text handlers live]
  (vector-suggestions text (keys (:sub handlers)) live))

(defn captured-events [records]
  ;; Avoid serializing transport payloads into a completion menu. IDs remain
  ;; available for every registered handler; only small readable args are offered.
  (->> records (filter #(= :epoch (:kind %))) (take-last 30) (map :event)
       (filter #(and (vector? %) (inline-complete? %))) distinct vec))

(defc <event-completion> [label id *text handlers records]
  (let [suggestions (vector-suggestions @*text (keys (:event handlers)) (captured-events records))]
    [:div
     [:label label " " [:input {:value @*text :list id
                                  :on-change #(scoped/>reset *text (.. % -target -value))}]]
     [:datalist {:id id}
      (for [event suggestions] ^{:key (pr-str event)} [:option {:value (pr-str event)}])]
     [:div.dev-subscriptions__suggestions {:aria-label (str label " suggestions")}
      (for [event suggestions] ^{:key (pr-str event)}
        [:button {:title (pr-str event) :on-click #(scoped/>reset *text (pr-str event))}
         (short-value event)])]]))

(defn path-completion [text]
  ;; Parse only EDN, including an unfinished vector. Completion never evaluates code.
  (try
    (let [trimmed (string/trim text)
          path (reader/read-string (if (string/ends-with? trimmed "]") trimmed (str trimmed "]")))
          append? (or (= trimmed "[") (string/ends-with? trimmed "]") (re-find #"\s$" text))]
      (when (vector? path)
        {:parent (if append? path (vec (butlast path)))
         :needle (if append? "" (pr-str (last path)))}))
    (catch :default _ nil)))

(defn dependency-queries [resource]
  (vec
    (distinct
      (concat
        (when-let [path (:into resource)] [[:component-data/installed path]])
        (case (:source resource)
          :subscription [(:query resource)]
          :app-db [[:dev-console/path (:path resource)]]
          :strapi (mapv #(vector :content [%]) (:keys resource))
          :supabase [[:component-data/supabase-cache (:query resource)]]
          [])))))

(defn scoped-targets [page-key active modules live]
  (let [mounted (for [[id {:keys [component path depends key]}] active :when path]
                  {:id [:mounted id] :scope :component :path path
                   :label (str (string/join "/" component) (when key (str " · " key)))
                   :depends depends})
        live (for [query live
                   :when (and (= :component-state/scoped-value (first query)) (vector? (second query))
                              (not (capture/own? query)))]
               {:id [:live query] :scope (first (second query)) :path (second query)
                :label (pr-str (second query))})]
    (:targets
      (reduce (fn [{:keys [seen] :as result} target]
                (if (contains? seen (:path target)) result
                    (-> result (update :seen conj (:path target)) (update :targets conj target))))
              {:seen #{} :targets []}
              (concat [{:id :page :scope :page :path [:page page-key] :label (str "Active page · " page-key)}
                  {:id :shared :scope :shared :path [:state] :label "Shared state"}]
                 (for [[id spec] modules] {:id [:module id] :scope :module :path [:module id]
                                           :label (str "Module · " id)
                                           :depends (vec (concat (when (seq (:content spec))
                                                                   [{:source :strapi :keys (:content spec)}])
                                                                 (when (vector? (:depends spec)) (:depends spec))))})
                 mounted live)))))

(defc <scoped-picker>
  {:state {:initial {:filter "" :page 0 :selected nil}}}
  [inspect!]
  :let [*filter (<sub :comp [:filter]) *page (<sub :comp [:page]) *selected (<sub :comp [:selected])]
  (let [debug @(rf/subscribe [:dev-console/data])
        _ @(rf/subscribe [:common/route])
        modules (keep (fn [[id _]] (when (loader/ready? id) [id (loader/code-spec id)])) loader/modules)
        targets (scoped-targets (restore/page-key) (:active debug) modules (tooling/live-query-vs))
        needle (string/lower-case @*filter)
        filtered (filterv #(string/includes? (string/lower-case (:label %)) needle) targets)
        {:keys [index items offset more?]} (layout/page filtered (or @*page 0))
        selected (some #(when (= @*selected (:id %)) %) targets)]
    [:section {:aria-label "Scoped state lookup"}
     [:h4 "Page, module and mounted component/view state"]
     [:label "Find state owner " [:input {:value @*filter
                                          :on-change #(do (>reset *filter (.. % -target -value)) (>reset *page 0))}]]
     [:div.dev-value__pagination
      [:button {:disabled (zero? index) :on-click #(>reset *page (dec index))} "Previous owners"]
      [:span (str (if (seq items) (inc offset) 0) "–" (+ offset (count items)) " of " (count filtered))]
      [:button {:disabled (not more?) :on-click #(>reset *page (inc index))} "Next owners"]]
     [:div.dev-subscriptions__suggestions
      (for [{:keys [id label path scope]} items] ^{:key (pr-str id)}
        [:button {:title (pr-str path)
                  :on-click #(do (>reset *selected id) (inspect! [:component-state/scoped-value path]))}
         (str (name scope) " · " label)])]
     (when selected
       [:<>
        [:code (pr-str (:path selected))]
        (when (seq (:depends selected))
          [:<>
           [:h4 "Resolved mounted dependencies"]
           [<value> [:owner-dependencies (:id selected)]
            (mapv (fn [resource]
                    {:resource resource
                     :status @(rf/subscribe [:dev-console/dependency-status resource])})
                  (:depends selected)) 0]
           [:div.dev-subscriptions__suggestions
            (for [query (take 10 (distinct (mapcat dependency-queries (:depends selected))))]
              ^{:key (pr-str query)}
              [:button {:title (pr-str query) :on-click #(inspect! query)} (short-value query)])]])])]))

(defc <subscriptions>
  {:state {:initial {:draft "[:common/route]" :query [:common/route]
                     :path-draft "[" :error nil}}}
  []
  :let [*draft (<sub :comp [:draft]) *query (<sub :comp [:query])
        *path (<sub :comp [:path-draft]) *error (<sub :comp [:error])]
  (let [handlers @(rf/subscribe [:dev-console/handlers])
        live (vec (distinct (tooling/live-query-vs)))
        suggestions (query-suggestions @*draft handlers live)
        {:keys [parent needle]} (path-completion @*path)
        keys (when parent @(rf/subscribe [:dev-console/path-keys parent needle]))
        run! (fn []
               (if-let [query (parse-query @*draft)]
                 (do (>reset *error nil) (>reset *query query))
                 (>reset *error "Enter an EDN subscription vector beginning with a keyword, e.g. [:blog/post 27].")))
        run-path! (fn []
                    (try
                      (let [path (reader/read-string @*path)]
                        (if (vector? path)
                          (do (>reset *error nil) (>reset *query [:dev-console/path path]))
                          (>reset *error "Enter an EDN vector path.")))
                      (catch :default _ (>reset *error "Enter a complete EDN vector path."))))]
    [:section.dev-subscriptions
     [:label "Subscription query "
      [:input {:value @*draft :list "dev-subscription-suggestions"
               :on-change #(>reset *draft (.. % -target -value))
               :on-key-down #(when (= "Enter" (.-key %)) (run!))}]]
     [:datalist {:id "dev-subscription-suggestions"}
      (for [query suggestions] ^{:key (pr-str query)} [:option {:value (pr-str query)}])]
     [:button {:on-click run!} "Run subscription"]
     [:div.dev-subscriptions__suggestions {:aria-label "Subscription suggestions"}
      (for [query suggestions] ^{:key (pr-str query)}
        [:button {:on-click #(>reset *draft (pr-str query)) :title "Fill query; edit arguments, then Run"}
         (short-value query)])]
     [:p "Choose a live query to reuse its arguments, or a registered ID and add arguments. Typing does not acquire data sources."]
     [:label "Subscription path "
      [:input {:value @*path :on-change #(>reset *path (.. % -target -value))
               :on-key-down #(when (= "Enter" (.-key %)) (run-path!))}]]
     [:button {:on-click run-path!} "Run path"]
     [:div.dev-subscriptions__suggestions {:aria-label "Path suggestions"}
      (for [key keys :let [path (conj parent key)]] ^{:key (pr-str path)}
        [:button {:on-click #(>reset *path (pr-str path))
                  :title (pr-str path)} (pr-str key)])]
     (when @*error [:p {:role "alert"} @*error])
     ^{:key (pr-str @*query)} [<query-result> @*query]
     [<scoped-picker> (fn [query]
                       (>reset *error nil) (>reset *draft (pr-str query)) (>reset *query query))]
     [:h4 "Live subscription queries"]
     [:p "Mounted query vectors, not app-db. The selected result is shown above."]
     [<value> [:live-subs] live 0]]))

(defc <record-group>
  {:state {:key (fn [group] (pr-str (:id group))) :initial {:open? false}}}
  [group]
  :let [*open (<sub :comp [:open?])]
  [:details.dev-event-group
   {:open (boolean @*open)
    :on-toggle #(let [open? (.. % -target -open)]
                  (when (not= open? (boolean @*open)) (>reset *open open?)))}
   [:summary
    [:code (pr-str (:id group))]
    [:strong (str " × " (:count group))]
    [:span (str " · " (duration-label (:duration group)) " total")]]
   (when @*open [<value> [:record-group (:id group)] (dissoc group :id) 0])])

(defc <record-groups>
  {:state {:key (fn [id & _] id) :initial {:page 0}}}
  [id records]
  :let [*page (<sub :comp [:page])]
  (let [groups (layout/group-records records)
        {:keys [items index offset more?]} (layout/page groups (or @*page 0))]
    [:section {:aria-label "Grouped records"}
     [:p (str (count records) " records in " (count groups) " groups. Event arguments do not create another group; expand for exact occurrences.")]
     [:div.dev-value__pagination
      [:button {:disabled (zero? index) :on-click #(>reset *page (dec index))} "Previous groups"]
      [:span (str (if (seq items) (inc offset) 0) "–" (+ offset (count items)) " of " (count groups))]
      [:button {:disabled (not more?) :on-click #(>reset *page (inc index))} "Next groups"]]
     (for [group items] ^{:key (pr-str (:id group))} [<record-group> group])]))

(defn trace-depth [index trace]
  (loop [id (:child-of trace) depth 0 seen #{}]
    (if (or (nil? id) (seen id) (nil? (get index id))) depth
        (recur (:child-of (get index id)) (inc depth) (conj seen id)))))
(defn timing-lanes [records]
  (let [index (into {} (map (juxt :id identity)) (filter #(= :trace (:kind %)) records))]
    (->> records
         (group-by #(if (= :trace (:kind %)) [:trace (trace-depth index %)] [(:kind %) (or (:instance %) "")]))
         (sort-by key))))

(defc <flamegraph>
  {:state {:initial {:epoch nil :selected nil :window 250 :offset 0}}}
  [records query-text]
  :let [*epoch (<sub :comp [:epoch]) *selected (<sub :comp [:selected])
        *window (<sub :comp [:window]) *offset (<sub :comp [:offset])]
  (let [epochs (vec (take-last 30 (filter #(and (= :epoch (:kind %)) (vector-matches? query-text (:event %))) records)))
        epoch (or (some #(when (= @*epoch (str (:dispatch-id %))) %) epochs) (last epochs))
        timings (filter #(and (number? (:start %)) (number? (:end %)) (number? (:duration %))) records)
        ;; Show one event and its following commits, rather than compressing minutes
        ;; of unrelated activity into bars too small to inspect.
        start (some-> (or (:start epoch) (when (string/blank? query-text) (some-> timings last :start))) (+ @*offset))
        end (when start (+ start @*window))
        timings (vec (take-last 100 (filter #(and (not= :epoch (:kind %)) start
                                                 (<= start (:start %) end)) timings)))
        span (when start (max 1 (- end start)))]
    [:div
     [:label "Event window "
      [:select {:value (str (:dispatch-id epoch))
                :on-change #(>reset *epoch (.. % -target -value))}
       (for [epoch epochs] ^{:key (:dispatch-id epoch)}
         [:option {:value (str (:dispatch-id epoch))}
          (str (:dispatch-id epoch) " · " (short-value (:event epoch)))])]]
     (when (and (not (string/blank? query-text)) (empty? epochs))
       [:p "No captured event matches this vector. Choose a recorded event or clear the filter."])
     [:div
      [:button {:on-click #(>reset *offset (- @*offset (/ @*window 2)))} "Pan earlier"]
      [:button {:on-click #(>reset *window (max 10 (/ @*window 2)))} "Zoom in"]
      [:button {:on-click #(>reset *window (min 10000 (* @*window 2)))} "Zoom out"]
      [:button {:on-click #(>reset *offset (+ @*offset (/ @*window 2)))} "Pan later"]
      [:button {:on-click #(do (>reset *offset 0) (>reset *window 250))} "Reset window"]]
     [:p "Nested re-frame traces, measured view bodies and React commit durations. Click a component bar to inspect/highlight it on the page. Profiler durations include child work; temporal proximity does not establish causation."]
     [:div.dev-flamegraph
      (if (empty? timings) [:p "No timing records in this window. Navigate or interact with a component."]
        (for [[lane traces] (timing-lanes timings)] ^{:key (pr-str lane)}
          [:div.dev-flamegraph__row
           [:span.dev-flamegraph__label (str (name (first lane)) " " (second lane))]
           [:div.dev-flamegraph__track
            (for [[i trace] (map-indexed vector traces)] ^{:key i}
              [:button.dev-flamegraph__bar
               {:on-click #(do (>reset *selected trace)
                                (when (:instance trace)
                                  (rf/dispatch [:dev-console/pick (:instance trace)])))
                :style {:left (str (* 100 (/ (- (:start trace) start) span)) "%")
                        :width (str (max 0.5 (* 100 (/ (:duration trace) span))) "%")}
                :title (str (or (:operation trace) (:component trace)) " · " (:duration trace) "ms")}
               (str (or (:operation trace) (:component trace)) " " (.toFixed (:duration trace) 2) "ms")])]]))]
     (when @*selected [<value> [:selected-timing] @*selected 0])]))

(defn timing-records [records hide-subs?]
  (if hide-subs? (filterv #(not= :sub/run (:op-type %)) records) records))

(defc <timings>
  {:state {:initial {:event "" :hide-subs? true}}}
  [records]
  :let [*event (<sub :comp [:event]) *hide (<sub :comp [:hide-subs?])]
  (let [handlers @(rf/subscribe [:dev-console/handlers])
        visible (timing-records records @*hide)]
    [:<>
     [<event-completion> "Timing event vector" "dev-timing-events" *event handlers records]
     [:label [:input {:type "checkbox" :checked (boolean @*hide)
                      :on-change #(>reset *hide (.. % -target -checked))}] "Hide :sub/run"]
     [:p "The vector filters captured event windows; [event-id] matches all its arguments. Grouped history below uses the same subscription-trace visibility setting."]
     [<flamegraph> visible @*event]
     [<record-groups> :timings visible]]))

(defc <layout-graph>
  {:state {:initial {:window "30000" :delayed-only? false :selected nil}}}
  [records]
  :let [*window (<sub :comp [:window]) *delayed (<sub :comp [:delayed-only?])
        *selected (<sub :comp [:selected])]
  (let [window (case @*window "10000" 10000 "30000" 30000 nil)
        {:keys [start span shifts peak total delayed context]} (layout/timeline records window @*delayed)
        x (fn [at] (+ 60 (* 920 (/ (- at start) span))))
        y (fn [value] (- 175 (* 135 (/ value peak))))
        selected @*selected]
    [:section.dev-layout {:aria-label "Layout shift timeline"}
     [:div.dev-layout__controls
      [:label "Time window " [:select {:value @*window :on-change #(>reset *window (.. % -target -value))}
                               [:option {:value "10000"} "Last 10 seconds"]
                               [:option {:value "30000"} "Last 30 seconds"]
                               [:option {:value "all"} "All retained shifts"]]]
      [:label [:input {:type "checkbox" :checked (boolean @*delayed)
                        :on-change #(>reset *delayed (.. % -target -checked))}] "Outside recent input"]]
     [:p (str (count shifts) " shifts · " delayed " outside recent input · recorded score sum " (.toFixed total 5))]
     [:p "Click a shift for moved elements, before/after rectangles, time since input, and nearby events/commits. Nearby records show timing correlation, not a proven cause. Inspector-only shifts are excluded."]
     [:svg.dev-layout__graph {:viewBox "0 0 1000 260" :role "group" :aria-label "Shift scores and correlated event/render markers"}
      (for [fraction [0 0.5 1]] ^{:key fraction}
        [:g [:line {:x1 60 :x2 980 :y1 (y (* peak fraction)) :y2 (y (* peak fraction)) :class "dev-layout__grid"}]
         [:text {:x 4 :y (+ 4 (y (* peak fraction)))} (.toFixed (* peak fraction) 4)]])
      (for [fraction [0 0.25 0.5 0.75 1]] ^{:key fraction}
        [:text {:x (x (+ start (* span fraction))) :y 255 :text-anchor "middle"}
         (str (.toFixed (/ (* span fraction) 1000) 1) "s")])
      [:text {:x 4 :y 210} "Events"] [:text {:x 4 :y 235} "Renders"]
      (for [[i record] (map-indexed vector context)] ^{:key i}
        [:line {:x1 (x (:start record)) :x2 (x (:start record))
                :y1 (if (= :epoch (:kind record)) 200 225) :y2 (if (= :epoch (:kind record)) 212 237)
                :class (str "dev-layout__" (name (:kind record)))}
         [:title (short-value (or (:event record) (:component record)))]] )
      (for [[i shift] (map-indexed vector shifts)] ^{:key [(:start shift) i]}
        [:g {:class (str "dev-layout__shift " (when (:user-input? shift) "dev-layout__shift--input"))}
         [:line {:x1 (x (:start shift)) :x2 (x (:start shift)) :y1 175 :y2 (y (:value shift))}]
         [:circle {:cx (x (:start shift)) :cy (y (:value shift)) :r 5 :role "button" :tab-index 0
                   :aria-label (str "Shift " (inc i) ", score " (.toFixed (:value shift) 5))
                   :on-click #(>reset *selected shift)
                   :on-key-down #(when (#{"Enter" " "} (.-key %)) (.preventDefault %) (>reset *selected shift))}
          [:title (str (short-value (mapv #(or (:component %) (:element %)) (:sources shift)))
                       " · " (when (:since-input shift) (str (.toFixed (:since-input shift) 0) "ms since input")))]]])]
     (when (empty? shifts) [:p "No page shifts in this window. Expand a comment or navigate while recording."])
     (when selected
       [<value> [:layout-selected] {:shift selected :nearby (layout/nearby records selected)} 0])
     [<value> [:layout-records] {:shifts shifts :renders (filterv #(= :render (:kind %)) records)} 0]]))

(declare <component-branch>)

(defc <component-branches>
  {:state {:key (fn [owner & _] owner) :initial {:page 0}}}
  [owner nodes depth inspect!]
  :let [*page (<sub :comp [:page])]
  (let [{:keys [items index offset more?]} (layout/page nodes (or @*page 0))]
    [:div.dev-component-tree__children
     (for [node items] ^{:key (pr-str (:id node))}
       [<component-branch> node depth inspect!])
     (when (or (pos? index) more?)
       [:div.dev-value__pagination
        [:button {:disabled (zero? index) :on-click #(>reset *page (dec index))} "Previous components"]
        [:span (str (inc offset) "–" (+ offset (count items)) " of " (count nodes))]
        [:button {:disabled (not more?) :on-click #(>reset *page (inc index))} "Next components"]])]))

(defc <component-branch>
  {:state {:key (fn [node & _] (pr-str (:id node)))}}
  [node depth inspect!]
  :let [*open (<sub :comp [:open?] {:initial (and (zero? depth) (= :mounted (:status node)))})
        *details (<sub :comp [:details?] {:initial false})]
  (let [{:keys [id component children status path key observed?]} node
        branch? (seq children)
        mounted? (= status :mounted)
        namespace? (= status :namespace)]
    [:div.dev-component-tree__node {:class (str "dev-component-tree__node--" (name status))}
     [:div.dev-component-tree__row
      [:button.dev-component-tree__branch
       {:disabled (not branch?) :aria-expanded (when branch? (boolean @*open))
        :on-click #(>update *open not) :title (string/join "/" component)}
       [:span.dev-component-tree__arrow (if branch? (if @*open "▾" "▸") "·")]
       [:code (if namespace? (first component) (last component))]
       (when (some? key) [:span.dev-component-tree__key (pr-str key)])
       (when branch? [:span.dev-value__count (count children)])]
      (when-not namespace?
        [:span.dev-component-tree__status
         {:title (if mounted? "Live mounted instance" (if observed? "Loaded declaration at a previously observed parent" "Loaded declaration; no unambiguous parent has been observed"))}
         (if mounted? "mounted" "ready · unmounted")])
      (when-not namespace?
        [:button {:on-click #(>update *details not) :aria-expanded (boolean @*details)} "Details"])
      (when mounted?
        [:button {:on-click #(rf/dispatch [:dev-console/pick id])} "Show on page"])]
     (when @*details
       [:div.dev-component-tree__details
        (when path [:button {:on-click #(inspect! path)} "Inspect state path"])
        [<value> [:component id] (dissoc node :children) 0]
        (when (and mounted? path)
          [<value> [:component-state id] @(rf/subscribe [:dev-console/path path]) 0])])
     (when (and branch? @*open)
       [<component-branches> id children (inc depth) inspect!])]))

(defc <component-tree> [active inspect!]
  :let [*search (<sub :comp [:search] {:initial ""})]
  (let [catalog @(rf/subscribe [:dev-console/catalog])
        active (into {} (filter #(layout/matches? @*search (val %))) active)
        catalog (into {} (filter #(layout/matches? @*search (key %))) catalog)
        parents @(rf/subscribe [:dev-console/component-parents])
        {:keys [mounted ready mounted-count ready-count]} (components/tree active catalog parents)]
    [:section.dev-component-tree {:aria-label "Component hierarchy"}
     [:label "Find component " [:input {:type "search" :value @*search
                                        :on-change #(>reset *search (.. % -target -value))}]]
     [:p (str mounted-count " mounted instances · " ready-count " loaded, unmounted declarations. Native DOM nodes are omitted.")]
     [:h4 "Mounted hierarchy"]
     [<component-branches> :mounted-roots mounted 0 inspect!]
     [:h4 "Other ready components"]
     [:p "Previously observed children appear under their known parent when unambiguous. Other loaded declarations are grouped by namespace; inspecting them does not mount or preload them."]
     [<component-branches> :ready-roots ready 0 inspect!]]))

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


(defc <event-history> [records]
  :let [*search (<sub :comp [:search] {:initial ""})]
  [:section
   [:label "Search captured events " [:input {:type "search" :value @*search
                                              :on-change #(>reset *search (.. % -target -value))}]]
   [<record-groups> :epochs (filterv #(and (= :epoch (:kind %))
                                          (layout/matches? @*search (:event %))) records)]])

(defc <profile-summary> [records]
  :let [*search (<sub :comp [:search] {:initial ""})]
  (let [groups (layout/metrics records @*search)]
    [:section.dev-profile
     [:h3 "Where time goes"]
     [:label "Find component or subscription " [:input {:type "search" :value @*search
                                                       :on-change #(>reset *search (.. % -target -value))}]]
     [:p "Measured development work, sorted by total time. View is the component body; render includes descendants. Counts and averages cover the bounded captured window, not the entire session. Mount/update counts belong to React commits. Inspect source to distinguish processing and DOM interop from required lifecycle adapters."]
     [:div.dev-profile__table
      [:table
       [:thead [:tr (for [label ["Kind / owner" "Runs" "Mount / update" "Total" "Average" "Maximum" "Inspect"]]
                      ^{:key label} [:th label])]]
       [:tbody (for [{:keys [id count total average maximum mounts updates latest]} (take 100 groups)]
                 ^{:key (pr-str id)}
                 [:tr [:td [:code (pr-str id)]] [:td count] [:td (str mounts " / " updates)]
                  [:td (duration-label total)] [:td (duration-label average)] [:td (duration-label maximum)]
                  [:td (when (:instance latest)
                         [:button {:on-click #(rf/dispatch [:dev-console/pick (:instance latest)])} "Show on page"])]])]]]]))

(defn element-for-instance [instance]
  (some #(when (= instance (.getAttribute % "data-dev-instance")) %)
        (array-seq (.querySelectorAll js/document "[data-dev-instance]"))))
(defn picking! []
  ;; Capture listeners prevent the selection click from activating the real UI.
  ;; Measurement and pointer intent are the only imperative DOM work here.
  (let [click! (fn [event]
                 (when-not (.closest (.-target event) "[data-dev-console]")
                   (when-let [element (.closest (.-target event) "[data-dev-instance]")]
                     (.preventDefault event) (.stopPropagation event)
                     (rf/dispatch [:dev-console/pick (.getAttribute element "data-dev-instance")]))))
        escape! #(when (= "Escape" (.-key %)) (rf/dispatch [:dev-console/picking false]))]
    (.addEventListener js/document "click" click! true)
    (.addEventListener js/document "keydown" escape!)
    #(do (.removeEventListener js/document "click" click! true)
         (.removeEventListener js/document "keydown" escape!))))

(defc <selection-highlight> [instance]
  (let [[rect set-rect!] (rf/use-state nil)]
    (rf/use-layout-effect
      (fn []
        (let [measure! (fn []
                         (if-let [element (element-for-instance instance)]
                           (let [rect (.getBoundingClientRect element)
                                 unit (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))]
                             (set-rect! {:left (str (/ (.-left rect) unit) "rem")
                                         :top (str (/ (.-top rect) unit) "rem")
                                         :width (str (/ (.-width rect) unit) "rem")
                                         :height (str (/ (.-height rect) unit) "rem")}))
                           (set-rect! nil)))
              observer (when (exists? js/ResizeObserver) (js/ResizeObserver. measure!))]
          (measure!)
          (when-let [element (element-for-instance instance)] (when observer (.observe observer element)))
          (.addEventListener js/window "scroll" measure! true)
          (.addEventListener js/window "resize" measure!)
          #(do (when observer (.disconnect observer))
               (.removeEventListener js/window "scroll" measure! true)
               (.removeEventListener js/window "resize" measure!)))) [instance])
    (when rect [:div.dev-selection-highlight {:style rect :aria-hidden true}])))

(defc <component-inspector>
  {:depends [source/catalog-resource]
   :loading-tag :section.dev-component-inspector}
  [instance record records]
  (let [catalog (:value @(rf/subscribe [:component-data/installed source/catalog-path]))
        {:keys [component path queries]} record
        queries (remove #(contains? #{:get :state :debug :component-state/scoped-value} (first %)) queries)
        declaration (get @(rf/subscribe [:dev-console/catalog]) component)
        anchor (:anchor-instance record)
        location (or (:source record) (get-in declaration [:options :source]))
        file (or (source/resolve-file catalog (:file location))
                 (source/resolve-file catalog (first component)))
        recent (vec (take-last 8 (filter #(= instance (:instance %)) records)))]
    [:section.dev-component-inspector
     {:aria-label "Selected component"
      :class (when-not anchor "dev-component-inspector--detached")
      :style (when anchor {:position-anchor (str "--dev-instance-" anchor)})}
     [:div.dev-component-inspector__heading
      [:strong {:title (string/join "/" component)} (or (last component) "Unmounted component")]
      [:button {:on-click #(rf/dispatch [:dev-console/pick nil]) :aria-label "Close selected component"} "×"]]
     [:small.dev-component-inspector__namespace (first component)]
     (if record
       [:<>
        (when (:unmounted? record) [:p {:role "status"} "This instance has unmounted; its last declaration and retained state are shown."])
        (when (and (not (:unmounted? record)) (nil? anchor))
          [:p {:role "status"} "This component currently has no native anchor."])
        (when file [:a {:href (source/source-url file (:line location))} "Open definition in source browser"])
        (if path [:<> [:h4 "Automatic app-db path"] [<value> [:picked-path instance] path 0]
                     [<value> [:picked-state instance] @(rf/subscribe [:dev-console/path path]) 0]]
            [:p "This view has no automatic scoped state."])
        [:h4 "Subscriptions acquired by this view"]
        (when (empty? queries) [:p "No subscriptions captured for this view."])
        (for [query queries]
          ^{:key (pr-str query)}
          [:details [:summary [:code (pr-str query)]] [<query-result> query]])
        [:h4 "Recent renders and observed reasons"]
        (if (seq recent) [<value> [:picked-renders instance] recent 0]
            [:p "No retained render samples for this instance."])
        [:small "Reasons compare arguments and cached query results. Other changes remain unclassified."]]
       [:p "This instance has unmounted. Select another component."])]))

(defc <page-diagnostics> [debug]
  (let [picking? (:picking? debug)
        instance (:selected-instance debug)
        anchor (first (components/native-roots (:active debug) instance))]
    (rf/use-effect #(when picking? (picking!)) [picking?])
    [:<>
     (when picking? [:div.dev-picking-hint {:role "status"} "Click a component to inspect it · Escape cancels"])
     (when instance
       [:<> (when anchor [<selection-highlight> anchor])
        [<component-inspector> instance
         (some-> (or (get (:active debug) instance)
                     (some-> (:selected-record debug) (assoc :unmounted? true)))
                 (assoc :anchor-instance anchor))
         (:records debug)]])]))

(declare <panel>)

(defn open-popout! []
  ;; The second document is a portal host. It shares this runtime and is never
  ;; bootstrapped as another app or fed a copied app-db.
  (js/window.open "about:blank" "tolgraven-dev-console" "popup,width=1100,height=760"))
(defc <popout> [window close!]
  (rf/use-effect
    (fn []
      (let [document (.-document window)]
        (set! (.-title document) "tolgrAVen · development diagnostics")
        (.addEventListener window "beforeunload" close!)
        #(do (.removeEventListener window "beforeunload" close!)
             (when-not (.-closed window) (.close window))))) [window])
  [:<>
   (rf/create-portal
     (rf/as-element
       [:<> (for [[index element] (map-indexed vector (array-seq (.querySelectorAll js/document "link[rel=stylesheet],style")))]
              ^{:key index}
              (if (= "STYLE" (.-tagName element))
                [:style (.-textContent element)]
                [:link {:rel "stylesheet" :href (.-href element)}]))]) (.-head (.-document window)))
   (rf/create-portal
     (rf/as-element [:aside.dev-console.dev-console--popout {:data-dev-console true}
                      [<panel> {:close! close!}]]) (.-body (.-document window)))])

(defc <panel>
  {:state {:initial {:tab :page :path "[:state]"
                     :value "nil" :event "[:page/preload-links]" :dry-run? false}}}
  [{:keys [close!]}]
  :let [*tab (<sub :comp [:tab])
        *path (<sub :comp [:path]) *value (<sub :comp [:value])
        *event (<sub :comp [:event]) *dry-run (<sub :comp [:dry-run?])]
  (let [options @(rf/subscribe [:dev-console/options])
        debug @(rf/subscribe [:dev-console/data])
        snapshot @(rf/subscribe [:dev-console/snapshot])
        route @(rf/subscribe [:common/route])
        tab-value @*tab
        records (:records debug)]
    [:section.dev-console__panel {:aria-label "Re-frame inspector"}
     [:div.dev-console__header
      [:strong "tolgrAVen · re-frame inspector"]
      [:a {:href "/docs/source"} "Source browser"]
      [:span.dev-console__metrics (str (count (:active debug)) " components · " (count records) " records")]
      [:label [:input {:type "checkbox" :checked (boolean (:recording? options))
                       :on-change #(rf/dispatch [:dev-console/option :recording? (.. % -target -checked)])}] "Record"]
      [:label [:input {:type "checkbox" :checked (boolean (:hydration-highlight? options))
                       :on-change #(rf/dispatch [:dev-console/option :hydration-highlight? (.. % -target -checked)])}] "Hydration flash"]
      [:button {:on-click #(rf/dispatch [:dev-console/clear])} "Clear"]
      [:button {:on-click #(rf/dispatch [:dev-console/copy (pr-str snapshot)])} "Copy snapshot"]
      [:button.dev-console__close {:aria-label "Close inspector" :on-click close!} "×"]]
     [:div.dev-console__tabs {:role "group" :aria-label "Inspector sections"}
      (for [tab [:page :components :modules :state :subs :loads :events :errors :timings :layout :code]]
        ^{:key tab} [:button {:class (when (= tab tab-value) "active")
                             :aria-pressed (= tab tab-value) :on-click #(>reset *tab tab)} (name tab)])]
     [:div.dev-console__body
      (case @*tab
        :page [<value> [:page] {:route route :spec (:data route)
                              :page-state @(rf/subscribe [:dev-console/path [:page (restore/page-key)]])} 0]
        :components [<component-tree> (:active debug)
                     (fn [path] (>reset *path (pr-str path)) (>reset *tab :state))]
        :modules [<value> [:modules]
                  {:pages (reitit/routes routes/router)
                   :modules (into {} (for [[id loadable] loader/modules]
                                       [id (if (loader/ready? id) @loadable :code-not-loaded)]))
                   :module-state @(rf/subscribe [:dev-console/path [:module]])} 0]
        :state [:<> [<path-inspector> *path *value] [<value> [:app-db] @(rf/subscribe [:dev-console/db]) 0]]
        :subs [<subscriptions>]
        :loads [<value> [:loads] {:resources @(rf/subscribe [:dev-console/resources])
                                 :loading @(rf/subscribe [:loading])
                                 :store @(rf/subscribe [:dev-console/path [:store]])} 0]
        :events [:<>
                 [<event-completion> "Event vector" "dev-dispatch-events" *event
                  @(rf/subscribe [:dev-console/handlers]) records]
                 [:label [:input {:type "checkbox" :checked (boolean @*dry-run) :on-change #(>reset *dry-run (.. % -target -checked))}]
                  "Stub common network/navigation/storage effects (other effects still run)"]
                 [:button {:on-click #(rf/dispatch [:dev-console/dispatch {:event @*event :dry-run? @*dry-run}])} "Dispatch and trace"]
                 [<value> [:dispatch-result] (:result debug) 0]
                 [<event-history> records]]
        :errors [:<> [validation/<reports>]
                 [<value> [:errors] (dissoc @(rf/subscribe [:dev-console/path [:diagnostics]]) :validation) 0]]
        :timings [:<> [<profile-summary> records] [<timings> records]]
        :layout [:<>
                 (if (and (exists? js/PerformanceObserver)
                          (some #{"layout-shift"} (array-seq (.-supportedEntryTypes js/PerformanceObserver))))
                   [<layout-graph> records]
                   [:p "This browser does not provide layout-shift observation. React render timings are still available."])]
        :code [<value> [:code] {:components @(rf/subscribe [:dev-console/catalog])
                                :registrations (into {} (for [[kind handlers] @(rf/subscribe [:dev-console/handlers])]
                                                         [kind (into {} (map (fn [[id handler]] [id (meta handler)]) handlers))]))} 0])]]))

(defc <console> []
  (let [options @(rf/subscribe [:dev-console/options])
        debug @(rf/subscribe [:dev-console/data])
        open? (boolean (:open? debug))
        [popout set-popout!] (rf/use-state nil)
        active? (boolean (or open? popout (:page-capture? options) (:picking? debug) (:selected-instance debug)))]
    (rf/use-layout-effect #(when active? (capture/connect!)) [active?])
    (rf/use-effect #(when (and active? (:recording? options)) (capture/start!))
                   [active? (:recording? options)])
    (rf/use-effect (fn [] (reset! capture/*flash? (boolean (:event-flash? options))) nil) [(:event-flash? options)])
    (rf/use-effect
      (fn []
        (let [handler (fn [event]
                        (when (and (= "D" (string/upper-case (.-key event)))
                                   (.-altKey event) (.-shiftKey event))
                          (.preventDefault event) (rf/dispatch [:dev-console/toggle-open])))]
          (.addEventListener js/window "keydown" handler)
          #(.removeEventListener js/window "keydown" handler))) [])
    [:aside.dev-console {:aria-label "Development console" :data-dev-console true}
     [:div.dev-console__tools
      [:button.dev-console__toggle {:on-click #(rf/dispatch [:dev-console/toggle-open]) :aria-expanded (boolean open?)}
       "Dev " (if open? "×" "⌘")]
      [:button {:on-click #(rf/dispatch [:dev-console/picking (not (:picking? debug))])
                :aria-pressed (boolean (:picking? debug))} "Pick component"]
      [:button {:on-click #(rf/dispatch [:dev-console/option :page-capture? (not (:page-capture? options))])
                :aria-pressed (boolean (:page-capture? options))} (if (:page-capture? options) "Stop capture" "Capture page")]
      [:label [:input {:type "checkbox" :checked (boolean (:event-flash? options))
                       :on-change #(rf/dispatch [:dev-console/option :event-flash? (.. % -target -checked)])}] "Event flashes"]
      [:button {:on-click #(if popout (.focus popout)
                              (when-let [window (open-popout!)] (set-popout! window)))} "Pop out"]]
     [<page-diagnostics> debug]
     (when (and open? (nil? popout)) [<panel> {:close! #(rf/dispatch [:dev-console/open false])}])
     (when popout [<popout> popout #(do (set-popout! nil) (rf/dispatch [:dev-console/open true]))])]))
