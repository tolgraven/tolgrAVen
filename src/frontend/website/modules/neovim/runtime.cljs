(ns website.modules.neovim.runtime
  "Lifecycle-owned browser/Neovim boundary. No worker is created during SSR."
  (:require [cljs.math :as math]
            [website.async :as a]
            ["./transport.js" :as transport]
            [website.modules.neovim.model :as model]
            [website.modules.neovim.files :as files]
            [website.modules.neovim.document :as document]
            [website.modules.neovim.profile :as profile]))

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

(def written-buffer-schema
  [:map
   [:path [:string {:max 1024}]]
   [:text [:string {:max 4194304}]]])

(def write-event "website-buffer-written")

(def ^:private watch-writes-lua
  "local channel, group, event = ...
   vim.api.nvim_create_autocmd('BufWritePost', {
     group = vim.api.nvim_create_augroup(group, {clear = true}),
     callback = function(args)
       local path = vim.api.nvim_buf_get_name(args.buf)
       local file = assert(io.open(path, 'rb'))
       local text = file:read('*a'); file:close()
       vim.rpcnotify(channel, event, path, text)
     end})")

(defn- notify-written! [payload on-write fail!]
  (let [[path text] (js->clj payload)]
    (if (and (string? path) (<= (count path) 1024)
             (string? text) (<= (count text) 4194304))
      (try (on-write {:path path :text text})
           (catch :default error (fail! error)))
      (fail! (js/Error. "Written buffer exceeds the supported bounds")))))

(defn- ^:async install-write-watch! [session group *active? fail!]
  (try
    (let [info (await (request! session "nvim_get_api_info" []))]
      (when @*active?
        (await (request! session "nvim_exec_lua"
                         [watch-writes-lua [(aget info 0) group write-event]]))))
    (catch :default error
      (when @*active? (fail! error)))))

(defn watch-writes!
  "Acquire one native BufWritePost owner. Cleanup releases RPC delivery and its
   exact autocommand group; callbacks never outlive the mounted editor."
  [^js session on-write fail!]
  (let [*active? (atom true)
        group (str "WebsiteWrites" (random-uuid))
        receive! #(when @*active? (notify-written! % on-write fail!))
        release! (.listen session write-event receive!)
        stop! (fn []
                (reset! *active? false)
                (release!)
                (-> (request! session "nvim_exec_lua"
                              ["local group = ...; pcall(vim.api.nvim_del_augroup_by_name, group)" [group]])
                    (a/catch-error (constantly nil))))]
    {:ready (install-write-watch! session group *active? fail!)
     :stop! stop!}))

(defn- ^:async open-editable! [rpc! {:keys [initial-file initial-text filetype]}]
  (if initial-file
    (await (rpc! "nvim_exec_lua"
                 ["local path = ...; assert(not path:match('^/') and not path:find('..', 1, true), 'Invalid initial file'); vim.cmd('hide edit ' .. vim.fn.fnameescape('/workspace/tolgrAVen/' .. path))"
                  [initial-file]]))
    (do
      (await (files/write! rpc! {:path "buffer" :file (js/Blob. #js [(or initial-text "")])}))
      (await (files/open! rpc! "buffer"))))
  (await (rpc! "nvim_set_option_value" ["fixendofline" false {:buf 0}]))
  (rpc! "nvim_set_option_value" ["filetype" filetype {:buf 0}]))

(defn- ^:async initialize! [session {:keys [cols rows document] :as options} configuring! publish!]
  (let [rpc! #(request! session %1 %2)
        attach! #(rpc! "nvim_ui_attach" [cols rows {:rgb true :ext_linegrid true}])]
    (try
      (await (.-ready session))
      (let [info (await (rpc! "nvim_get_api_info" []))]
        (configuring!)
        (.registerTypes session (.-types (aget info 1))))
      (await (profile/configure! rpc!))
      (if document
        ;; Diff windows need the measured UI in place before their splits are built.
        (do (await (attach!)) (await (document/open! rpc! document)))
        (do (await (open-editable! rpc! options)) (await (attach!))))
      (await (rpc! "nvim_command" ["redraw"]))
      (publish! :ready "Ready")
      session
      (catch :default error
        (publish! :error (.-message error))
        (.stop session)
        nil))))

(defn start! [options redraw! status!]
  (let [*active? (atom true)
        *bootstrap? (atom true)
        publish! (fn [status message] (when @*active? (status! status message)))
        configuring! #(do (reset! *bootstrap? false)
                          (publish! :booting "Configuring editor and repository…"))
        session (transport/start worker-path
                  #(when @*active? (redraw! (js->clj % :keywordize-keys true)))
                  #(when @*bootstrap? (publish! :booting %))
                  #(publish! :exited (str "Neovim exited (" % ")"))
                  #(publish! :error %))]
    {:session session
     :ready (initialize! session options configuring! publish!)
     :stop! #(do (reset! *active? false) (.stop session))}))

(defn column-count [element probe]
  (let [cell-width (.-width (.getBoundingClientRect probe))]
    (max 20 (min 240 (math/floor (/ (.-clientWidth element) cell-width))))))

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
