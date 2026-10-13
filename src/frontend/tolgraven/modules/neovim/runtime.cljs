(ns tolgraven.modules.neovim.runtime
  "Lifecycle-owned browser/Neovim boundary. No worker is created during SSR."
  (:require ["./transport.js" :as transport]
            [clojure.string :as string]
            [tolgraven.modules.neovim.model :as model]
            [tolgraven.modules.neovim.profile :as profile]))

(def worker-path "/vendor/neovim/nvim-profile-worker.js")

(defn available? []
  (and (exists? js/window)
       js/window.crossOriginIsolated
       (exists? js/SharedArrayBuffer)
       (exists? js/Worker)
       (exists? js/WebAssembly)))

(defn unavailable-message []
  (cond
    (not (exists? js/window)) "Neovim starts in a browser."
    (not js/window.isSecureContext) "Neovim needs a secure page. Use HTTPS or a trusted localhost URL."
    (not js/window.crossOriginIsolated) "Open the isolated Neovim page to start the editor."
    (not (exists? js/SharedArrayBuffer)) "SharedArrayBuffer is unavailable in this browser context. Check browser restrictions on shared memory."
    (not (exists? js/WebAssembly)) "WebAssembly is unavailable in this browser context."
    :else "Browser workers are unavailable in this browser context."))

(defn request! [session method params]
  (.request session method (clj->js params)))

(defn start! [{:keys [cols rows initial-text initial-file filetype]} redraw! status!]
  (let [*active? (atom true)
        *ready? (atom false)
        *bootstrap? (atom true)
        publish! (fn [status message] (when @*active? (status! status message)))
        session (transport/start worker-path
                  #(when @*active? (redraw! (js->clj % :keywordize-keys true)))
                  #(when (and @*bootstrap? (not @*ready?)) (publish! :booting %))
                  #(publish! :exited (str "Neovim exited (" % ")"))
                  #(publish! :error %))
        ready (-> (.-ready session)
                  (.then #(request! session "nvim_get_api_info" []))
                  (.then (fn [info]
                           (reset! *bootstrap? false)
                           (publish! :booting "Configuring editor and repository…")
                           (.registerTypes session (.-types (aget info 1)))
                           (profile/configure! #(request! session %1 %2))))
                  (.then #(if initial-file
                            (request! session "nvim_exec_lua"
                              ["local path = ...; assert(not path:match('^/') and not path:find('..', 1, true), 'Invalid initial file'); vim.cmd('hide edit ' .. vim.fn.fnameescape('/workspace/tolgrAVen/' .. path))" [initial-file]])
                            (request! session "nvim_buf_set_lines"
                              [0 0 -1 true (string/split (or initial-text "") #"\n" -1)])))
                  (.then #(request! session "nvim_set_option_value"
                            ["filetype" filetype {:buf 0}]))
                  ;; Attach only after the initial buffer and its syntax are ready.
                  (.then #(request! session "nvim_ui_attach" [cols rows {:rgb true, :ext_linegrid true}]))
                  (.then #(request! session "nvim_command" ["redraw"]))
                  (.then #(do (reset! *ready? true) (publish! :ready "Ready") session))
                  (.catch (fn [error]
                            (publish! :error (.-message error))
                            (.stop session)
                            nil)))]
    {:session session
     :ready ready
     :stop! #(do (reset! *active? false) (.stop session))}))

(defn column-count [element probe]
  (let [cell-width (.-width (.getBoundingClientRect probe))]
    (max 20 (min 240 (js/Math.floor (/ (.-clientWidth element) cell-width))))))

(defn resize! [session element probe rows]
  (request! session "nvim_ui_try_resize" [(column-count element probe) rows]))

(defn key-input [event]
  (model/key-input {:key (.-key event)
                    :ctrl? (.-ctrlKey event)
                    :alt? (.-altKey event)
                    :shift? (.-shiftKey event)
                    :meta? (.-metaKey event)
                    :composing? (.-isComposing ^js (.-nativeEvent event))}))

(defn mouse-params [grid element probe event button action]
  (let [rect (.getBoundingClientRect element)
        cell-width (.-width (.getBoundingClientRect probe))
        cell-height (.-height (.getBoundingClientRect (.querySelector element ".neovim__row")))
        [row col] (model/mouse-position {:x (.-clientX event)
                                        :y (.-clientY event)
                                        :left (.-left rect)
                                        :top (.-top rect)
                                        :cell-width cell-width
                                        :cell-height cell-height
                                        :cols (count (first (:cells grid)))
                                        :rows (count (:cells grid))})]
    [button action (model/mouse-modifiers {:shift? (.-shiftKey event)
                                         :ctrl? (.-ctrlKey event)
                                         :alt? (.-altKey event)
                                         :meta? (.-metaKey event)})
     0 row col]))
