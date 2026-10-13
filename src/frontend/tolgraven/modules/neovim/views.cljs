(ns tolgraven.modules.neovim.views
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.listener :as listener]
            [reagent.core :as r]
            [clojure.string :as string]
            [tolgraven.modules.neovim.model :as model]
            [tolgraven.modules.neovim.runtime :as runtime]
            [tolgraven.modules.neovim.files :as files]))

(def options-schema
  [:map
   [:?initial-file [:string {:max 512}]]
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
           {:class (when cursor? (str "neovim__cursor neovim__cursor--" (model/cursor-shape grid)))
            :style (assoc ((if cursor? model/cursor-style model/highlight-style)
                          grid (get-in grid [:highlights highlight]))
                          :width (str width "ch") :flex-basis (str width "ch"))}
           text])])]))

(defc ^:private <session>
  {:spec false}
  [options :- options-schema]
  :let [*view (r/atom {:status :booting, :message "Starting Neovim…", :grid (model/grid 80 24)})
        *imports (r/atom files/idle-state)
        *session (atom nil)
        *element (atom nil)
        *probe (atom nil)
        *grid-element (atom nil)
        *pointer (atom nil)]
  ;; The editor process owns its buffer. This ratom is its disposable UI projection,
  ;; like a native player/gesture adapter, never a mirrored application document store.
  (let [{:keys [rows cols initial-text initial-file filetype on-save]
         :or {rows 24, cols 80, initial-text "", filetype "clojure"}} options
        fail! #(swap! *view assoc :status :error :message (.-message %))
        request! (fn [method params]
                   (when-let [session (when (= :ready (:status @*view)) @*session)]
                     (-> (runtime/request! session method params)
                         (.then (fn [result]
                                  (when (and (identical? session @*session) @*grid-element)
                                    (.focus @*grid-element))
                                  result))
                         (.catch #(when (identical? session @*session) (fail! %))))))
        mouse-ready? #(and (= :ready (:status @*view)) (get-in @*view [:grid :mouse?]))
        mouse! (fn [event button action]
                 (when (mouse-ready?)
                   (let [params (runtime/mouse-params (:grid @*view) @*grid-element @*probe event button action)]
                     (request! "nvim_input_mouse" params)
                     params)))
        cancel-pointer! (fn [_]
                          (when-let [{:keys [params]} @*pointer]
                            (reset! *pointer nil)
                            (request! "nvim_input_mouse" (assoc params 1 "release"))))]
    (rf/use-effect
     (fn []
       (when (exists? js/window)
         (reset! *view {:status :booting
                       :message "Starting Neovim…"
                       :grid (model/grid cols rows)})
         (reset! *imports files/idle-state)
         (reset! *pointer nil)
         (if-not (runtime/available?)
           (do (swap! *view assoc :status :unsupported
                      :message (runtime/unavailable-message)) nil)
           (let [*active? (atom true)
                 *observer (atom nil)
                 {:keys [session ready stop!]}
                 (runtime/start! {:cols (runtime/column-count @*element @*probe)
                                  :rows rows
                                  :initial-text initial-text
                                  :initial-file initial-file
                                  :filetype filetype}
                   #(when @*active? (swap! *view update :grid model/redraw %))
                   #(when @*active? (swap! *view assoc :status %1 :message %2)))
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
               (reset! *pointer nil)
               (stop!))))))
     [cols rows initial-text initial-file filetype])
    (let [{:keys [status message grid]} @*view
          importing? (= :loading (:status @*imports))
          import! (fn [file-list folder?]
                    (when (pos? (.-length file-list))
                      (swap! *imports assoc :status :loading :message "Importing files…")
                      (let [session @*session]
                        (-> (files/import! #(runtime/request! session %1 %2) file-list folder?)
                          (.then (fn [paths]
                                   (when (identical? session @*session)
                                     (swap! *imports assoc
                                            :status :ready
                                            :paths (vec (distinct (concat (:paths @*imports) paths)))
                                            :selected (first paths)
                                            :message (str (count paths) " files imported into this session."))
                                     (when @*grid-element (.focus @*grid-element)))))
                          (.catch #(when (identical? session @*session)
                                     (swap! *imports assoc :status :error :message (.-message %))))))))]
      (rf/use-effect
       (fn []
         (when (= :ready status)
           (let [session @*session
                 focus! (fn [_]
                          (when (identical? session @*session)
                            (-> (runtime/request! session "nvim_ui_set_focus"
                                  [(and (not (.-hidden js/document)) (.hasFocus js/document))])
                                (.catch #(when (identical? session @*session) (fail! %))))))]
             (focus! nil)
             ;; React wheel listeners are passive. This owned native binding allows
             ;; Neovim to consume scrolling without also scrolling the host page.
             (listener/listen! :neovim
               (cond-> [{:target "window", :event "focus", :handler focus!}
                        {:target "window", :event "blur", :handler focus!}
                        {:target "document", :event "visibilitychange", :handler focus!}]
                 (:mouse? grid)
                 (conj {:target @*grid-element
                        :event "wheel"
                        :passive? false
                        :handler (fn [event]
                                   (when (and (mouse-ready?)
                                              (not (or (.-ctrlKey event) (.-metaKey event))))
                                     (let [x (.-deltaX event)
                                           y (.-deltaY event)
                                           delta (if (> (js/Math.abs x) (js/Math.abs y)) x y)
                                           action (if (> (js/Math.abs x) (js/Math.abs y))
                                                    (if (pos? x) "right" "left")
                                                    (if (pos? y) "down" "up"))]
                                       (when-not (zero? delta)
                                         (.preventDefault event)
                                         (mouse! event "wheel" action)))))}))))))
       [status (:mouse? grid)])
      [:div.neovim
       [:div.neovim__toolbar
        [:span {:role "status", :aria-live "polite"} (if (= :ready status) (:mode grid) message)]
        (when on-save
          [:button {:disabled (not= :ready status)
                    :on-click (fn [_]
                                (when-let [session @*session]
                                  (-> (runtime/request! session "nvim_buf_get_lines" [0 0 -1 true])
                                      (.then (fn [lines]
                                               (when (identical? session @*session)
                                                 (on-save (string/join "\n" (js->clj lines))))))
                                      (.catch #(when (identical? session @*session) (fail! %))))))}
           "Use buffer"])]
       (when (= :booting status)
         [:div.neovim__loading
          [:div.neovim__progress {:role "progressbar", :aria-label "Loading Neovim", :aria-valuetext message}
           [:span.neovim__progress-fill]]])
       (when (= :ready status)
         [:div.neovim__files
          [:button {:on-click #(request! "nvim_exec_lua" ["vim.cmd('cd /workspace/tolgrAVen'); vim.cmd('Meta!')" []])} "Search repository"]
          [:button {:on-click #(request! "nvim_exec_lua" ["vim.cmd('cd /workspace/tolgrAVen'); vim.cmd('Meta! #file')" []])} "Browse source files"]
          [:button {:on-click #(request! "nvim_command" ["NERDTreeToggle /workspace/tolgrAVen"])} "File tree"]
          [:label "Import files "
           [:input {:type "file", :multiple true, :disabled importing?
                    :on-change #(import! (.. % -target -files) false)}]]
          [:label "Import folder "
           [:input {:type "file", :multiple true, :webkitdirectory "", :disabled importing?
                    :on-change #(import! (.. % -target -files) true)}]]
          (when (seq (:paths @*imports))
            [:<>
             [:select {:aria-label "Imported file"
                       :value (:selected @*imports)
                       :disabled importing?
                       :on-change (fn [event]
                                    (let [path (.. event -target -value)
                                          session @*session]
                                      (-> (files/open! #(runtime/request! session %1 %2) path)
                                          (.then (fn [_]
                                                   (when (identical? session @*session)
                                                     (swap! *imports assoc :selected path)
                                                     (when @*grid-element (.focus @*grid-element)))))
                                          (.catch #(when (identical? session @*session) (fail! %))))))}
              (for [path (:paths @*imports)] ^{:key path} [:option {:value path} path])]
             [:button {:disabled importing?, :on-click #(request! "nvim_command" ["Meta!"])}
              "Search imported project"]])
          [:span {:role "status"} (:message @*imports)]
          [:small "Session files only. Up to 512 files, 4 MiB each, 32 MiB per import."]])
       (when (and (= :unsupported status)
                  (exists? js/window)
                  js/window.isSecureContext
                  (not js/window.crossOriginIsolated))
         ;; An isolation policy requires a new document, not a same-document SPA hop.
         [:p [:a {:href "/neovim", :target "_self", :data-document-navigation "true"} "Open Neovim"]])
       [:div.neovim__viewport {:ref #(reset! *element %)}
        [:span.neovim__measure {:aria-hidden true, :ref #(reset! *probe %)} "M"]
        ;; Preformatted markup keeps theme font overrides from changing cell metrics.
        [:pre.neovim__grid
         {:ref #(reset! *grid-element %)
          :tab-index 0
          :class (cond-> []
                   (not= :ready status) (conj "neovim__grid--loading")
                   (and (= :ready status) (:mouse? grid)) (conj "neovim__grid--mouse"))
          :role "textbox"
          :aria-label "Neovim editor"
          :aria-multiline true
          :aria-readonly (not= :ready status)
          :on-pointer-down (fn [event]
                             (when-let [button (model/mouse-button (.-button event))]
                               (when-let [params (mouse! event button "press")]
                                 (.preventDefault event)
                                 (.focus (.-currentTarget event))
                                 (.setPointerCapture (.-currentTarget event) (.-pointerId event))
                                 (reset! *pointer {:id (.-pointerId event), :button button, :params params}))))
          :on-pointer-move (fn [event]
                             (when-let [{:keys [id button]} @*pointer]
                               (when (= id (.-pointerId event))
                                 (when-let [params (mouse! event button "drag")]
                                   (swap! *pointer assoc :params params)))))
          :on-pointer-up (fn [event]
                           (when-let [{:keys [id button params]} @*pointer]
                             (when (= id (.-pointerId event))
                               (reset! *pointer nil)
                               (when-not (mouse! event button "release")
                                 (request! "nvim_input_mouse" (assoc params 1 "release"))))))
          :on-pointer-cancel cancel-pointer!
          :on-lost-pointer-capture cancel-pointer!
          :on-context-menu #(when (mouse-ready?) (.preventDefault %))
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
  "A lifecycle-owned Neovim WASM component. options: :initial-text or :initial-file, :filetype,
   :cols, :rows and optional :on-save (receives the current buffer as a string).
   Requires an isolated top-level document, as provided by /neovim.
   Changing initialization options starts a new session; unmount terminates it."
  {:spec false}
  [options :- options-schema]
  :let [*generation (r/atom 0)]
  [:div.neovim-container
   ^{:key @*generation} [<session> options]
   [:button {:on-click #(swap! *generation inc)} "Restart editor"]])
