(ns tolgraven.modules.neovim.views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [reagent.core :as r]
            [clojure.string :as string]
            [tolgraven.modules.neovim.model :as model]
            [tolgraven.modules.neovim.runtime :as runtime]))

(def options-schema
  [:map
   [:?initial-text [:string {:max 8192}]]
   [:?filetype [:string {:max 64}]]
   [:?cols [:int {:min 20, :max 240}]]
   [:?rows [:int {:min 8, :max 80}]]
   [:?on-save fn?]])

(defc ^:private <grid>
  [grid :- model/grid-schema]
  (let [[cursor-row cursor-col] (:cursor grid)]
    [:<>
     (for [[row cells] (map-indexed vector (:cells grid))]
       ^{:key row}
       [:span.neovim__row
        (for [{:keys [col width text highlight cursor?]}
              (model/row-runs cells (if (= row cursor-row) cursor-col -1))]
          ^{:key col}
          [:span.neovim__cell
           {:class (when cursor? "neovim__cursor")
            :style (assoc (model/highlight-style grid (get-in grid [:highlights highlight]))
                          :width (str width "ch") :flex-basis (str width "ch"))}
           text])])]))

(defc ^:private <session>
  {:spec false}
  [options :- options-schema]
  :let [*view (r/atom {:status :booting, :message "Starting Neovim…", :grid (model/grid 80 24)})
        *session (atom nil)
        *element (atom nil)
        *probe (atom nil)]
  ;; The editor process owns its buffer. This ratom is its disposable UI projection,
  ;; like a native player/gesture adapter, never a mirrored application document store.
  (let [{:keys [rows cols initial-text filetype on-save]
         :or {rows 24, cols 80, initial-text "", filetype "clojure"}} options
        fail! #(swap! *view assoc :status :error :message (.-message %))
        request! (fn [method params]
                   (when (= :ready (:status @*view))
                     (-> (runtime/request! @*session method params) (.catch fail!))))]
    (rf/use-effect
     (fn []
       (when (exists? js/window)
         (if-not (runtime/available?)
           (do (swap! *view assoc :status :unsupported
                      :message "Open the editor in its own page to start Neovim.") nil)
           (let [*active? (atom true)
                 *observer (atom nil)
                 {:keys [session ready stop!]}
                 (runtime/start! {:cols cols, :rows rows, :initial-text initial-text, :filetype filetype}
                   #(swap! *view update :grid model/redraw %)
                   #(swap! *view assoc :status %1 :message %2))
                 resize! (fn []
                           (when (and @*active? @*element @*probe)
                             (-> (runtime/resize! session @*element @*probe rows)
                                 (.catch #(when @*active? (fail! %))))))]
             (reset! *session session)
             (-> ready (.then (fn [result]
                               (when (and result @*active?)
                                 (resize!)
                                 (when (exists? js/ResizeObserver)
                                   (let [observer (js/ResizeObserver. resize!)]
                                     (reset! *observer observer)
                                     (.observe observer @*element)))))))
             (fn []
               (reset! *active? false)
               (when-let [observer @*observer] (.disconnect observer))
               (reset! *session nil)
               (stop!))))))
     [cols rows initial-text filetype])
    (let [{:keys [status message grid]} @*view]
      [:div.neovim
       [:div.neovim__toolbar
        [:span {:role "status", :aria-live "polite"} (if (= :ready status) (:mode grid) message)]
        (when on-save
          [:button {:disabled (not= :ready status)
                    :on-click #(-> (runtime/request! @*session "nvim_buf_get_lines" [0 0 -1 true])
                                   (.then (fn [lines] (on-save (string/join "\n" (js->clj lines)))))
                                   (.catch fail!))}
           "Use buffer"])]
       (when (= :unsupported status)
         ;; An isolation policy requires a new document, not a same-document SPA hop.
         [:p [:a {:href "/neovim", :target "_self", :data-document-navigation "true"} "Open Neovim"]])
       [:div.neovim__viewport {:ref #(reset! *element %)}
        [:span.neovim__measure {:aria-hidden true, :ref #(reset! *probe %)} "M"]
        ;; Preformatted markup keeps theme font overrides from changing cell metrics.
        [:pre.neovim__grid
         {:tab-index 0
          :role "textbox"
          :aria-label "Neovim editor"
          :aria-multiline true
          :aria-readonly (not= :ready status)
          :on-key-down (fn [event]
                         (when-let [input (runtime/key-input event)]
                           (when (= :ready status)
                             (.preventDefault event)
                             (request! "nvim_input" [input]))))
          :on-paste (fn [event]
                      (when (= :ready status)
                        (.preventDefault event)
                        (request! "nvim_paste" [(.getData (.-clipboardData event) "text/plain") true -1])))
          :on-composition-end (fn [event]
                                (when (seq (.-data event)) (request! "nvim_paste" [(.-data event) true -1])))}
         [<grid> grid]]]])))

(defc <editor>
  "A lifecycle-owned Neovim WASM component. options: :initial-text, :filetype,
   :cols, :rows and optional :on-save (receives the current buffer as a string).
   Requires an isolated top-level document, as provided by /neovim.
   Changing initialization options starts a new session; unmount terminates it."
  {:spec false}
  [options :- options-schema]
  :let [*generation (r/atom 0)]
  [:div.neovim-container
   ^{:key @*generation} [<session> options]
   [:button {:on-click #(swap! *generation inc)} "Restart editor"]])
