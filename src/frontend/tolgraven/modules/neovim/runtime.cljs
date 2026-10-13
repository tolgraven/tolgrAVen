(ns tolgraven.modules.neovim.runtime
  "Lifecycle-owned browser/Neovim boundary. No worker is created during SSR."
  (:require ["./transport.js" :as transport]
            [clojure.string :as string]
            [tolgraven.modules.neovim.model :as model]
            [tolgraven.modules.neovim.profile :as profile]))

(def worker-path "/vendor/neovim/nvim-profile-worker.js")

(defn available? []
  (and (exists? js/window) js/window.crossOriginIsolated (exists? js/SharedArrayBuffer)))

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
                  (.then #(request! session "nvim_ui_attach" [cols rows {:rgb true, :ext_linegrid true}]))
                  (.then #(if initial-file
                            (request! session "nvim_exec_lua"
                              ["local path = ...; assert(not path:match('^/') and not path:find('..', 1, true), 'Invalid initial file'); vim.cmd('hide edit ' .. vim.fn.fnameescape('/workspace/tolgrAVen/' .. path))" [initial-file]])
                            (request! session "nvim_buf_set_lines"
                              [0 0 -1 true (string/split (or initial-text "") #"\n" -1)])))
                  (.then #(request! session "nvim_set_option_value"
                            ["filetype" filetype {:buf 0}]))
                  (.then #(do (reset! *ready? true) (publish! :ready "Ready") session))
                  (.catch (fn [error]
                            (publish! :error (.-message error))
                            (.stop session)
                            nil)))]
    {:session session
     :ready ready
     :stop! #(do (reset! *active? false) (.stop session))}))

(defn resize! [session element probe rows]
  (let [cell-width (.-width (.getBoundingClientRect probe))
        cols (max 20 (min 240 (js/Math.floor (/ (.-clientWidth element) cell-width))))]
    (request! session "nvim_ui_try_resize" [cols rows])))

(defn key-input [event]
  (model/key-input {:key (.-key event)
                    :ctrl? (.-ctrlKey event)
                    :alt? (.-altKey event)
                    :shift? (.-shiftKey event)
                    :meta? (.-metaKey event)
                    :composing? (.-isComposing ^js (.-nativeEvent event))}))
