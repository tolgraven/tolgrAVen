(ns tolgraven.modules.neovim.document
  "Bounded, immutable source documents rendered by native Neovim windows."
  (:require [tolgraven.modules.neovim.files :as files]))

(def max-bytes (* 1024 1024))
(def file-schema [:map [:path [:string {:max 512}]] [:text [:string {:max 1048576}]]])
(def schema [:map [:mode [:enum :patch :diff]] [:files [:vector {:min 1, :max 2} file-schema]]])

(defn error [document]
  (let [{:keys [mode files]} document]
    (cond
      (not (#{:patch :diff} mode)) "Unsupported document layout."
      (not= (count files) (if (= :diff mode) 2 1)) "The document has an invalid number of files."
      (some #(not (files/safe-path? (:path %))) files) "A document has an unsupported path."
      (not= (count files) (count (set (map :path files)))) "Document paths must be unique."
      (some #(not (string? (:text %))) files) "Document text is missing."
      (some #(> (.-length (.encode (js/TextEncoder.) (:text %))) max-bytes) files)
      "Each source file must be at most 1 MiB."
      (some #(.includes (:text %) "\u0000") files) "Binary files cannot be shown as a text diff.")))

(def configure-lua
  "local mode, paths = ...
   vim.cmd('silent! only')
   vim.cmd('diffoff!')
   -- Viewers use absolute line numbers even after numbertoggle's WinEnter hook.
   vim.g.relativemode = 0
   vim.opt.equalalways = true
   vim.opt.diffopt = {'internal', 'filler', 'closeoff', 'algorithm:histogram', 'indent-heuristic'}
   for index, path in ipairs(paths) do
     if index == 2 then vim.cmd('silent rightbelow vnew') end
     vim.cmd('silent hide edit! ' .. vim.fn.fnameescape('/workspace/' .. path))
     vim.api.nvim_win_set_cursor(0, {1, 0})
     vim.cmd('silent normal! ggzt')
     if mode == 'patch' then vim.bo.filetype = 'diff' end
     vim.bo.readonly = true
     vim.bo.modifiable = false
     vim.bo.swapfile = false
     vim.wo.number = true
     vim.wo.relativenumber = false
     vim.wo.wrap = false
     vim.wo.colorcolumn = ''
     if mode == 'diff' then vim.cmd('silent diffthis') end
   end
   if mode == 'diff' then vim.cmd('silent wincmd h'); vim.cmd('silent diffupdate') end
   vim.cmd('silent wincmd =')
   vim.api.nvim_win_set_cursor(0, {1, 0})")

(defn open! [request! document]
  (if-let [message (error document)]
    (js/Promise.reject (js/Error. message))
    (-> (reduce (fn [pending {:keys [path text]}]
                  (.then pending #(files/write! request! {:path path, :file (js/Blob. #js [text])})))
                (js/Promise.resolve nil) (:files document))
        (.catch (fn [error]
                  (throw (js/Error. (str "Writing comparison files failed: " (.-message error))))))
        (.then (fn [_]
                 (-> (request! "nvim_exec_lua"
                               [configure-lua [(name (:mode document)) (mapv :path (:files document))]])
                     (.catch (fn [error]
                               (throw (js/Error. (str "Preparing comparison windows failed: " (.-message error))))))))))))
