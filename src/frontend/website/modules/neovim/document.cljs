(ns website.modules.neovim.document
  "Bounded, immutable source documents rendered by native Neovim windows."
  (:require [website.modules.neovim.files :as files]))

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
   -- Reset views after diff geometry exists. An empty/short side needs its
   -- leading diff filler visible; topfill=0 would bind the other pane to EOF.
   for _, win in ipairs(vim.api.nvim_list_wins()) do
     vim.wo[win].scrollbind = false
     vim.api.nvim_win_call(win, function()
       local filler = mode == 'diff' and vim.fn.diff_filler(1) or 0
       vim.fn.winrestview({topline = 1, lnum = 1, col = 0, topfill = filler})
     end)
   end
   if mode == 'diff' then
     for _, win in ipairs(vim.api.nvim_list_wins()) do vim.wo[win].scrollbind = true end
     -- Focusing an empty side forces its only real line into view, binding a
     -- long added/deleted side to EOF. Start in the side with actual content.
     local before = vim.fn.bufnr('/workspace/' .. paths[1])
     local after = vim.fn.bufnr('/workspace/' .. paths[2])
     local focus = vim.api.nvim_buf_line_count(after) >= vim.api.nvim_buf_line_count(before) and paths[2] or paths[1]
     vim.api.nvim_set_current_win(vim.fn.bufwinid('/workspace/' .. focus))
   end
   vim.api.nvim_win_set_cursor(0, {1, 0})
   vim.cmd('silent normal! ggzt')")

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
