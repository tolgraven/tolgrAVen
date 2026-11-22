(ns website.modules.neovim.files
  "User-selected File objects become session-local Neovim files, never host paths."
  (:require [website.async :as a]
            [clojure.string :as string]))

(def max-files 512)
(def max-file-bytes (* 4 1024 1024))
(def max-total-bytes (* 32 1024 1024))
(def idle-state {:status :idle, :paths [], :selected "", :message ""})

(def state-schema
  [:map
   [:status [:enum :idle :loading :ready :error]]
   [:paths [:vector string?]]
   [:selected string?]
   [:message string?]])

(defn safe-path? [path]
  (and (string? path) (seq path)
       (not (re-find #"[\\\u0000]" path))
       (every? #(and (seq %) (not (#{"." ".."} %))) (string/split path #"/" -1))))

(defn plan-error [descriptors]
  (cond
    (> (count descriptors) max-files) "Choose at most 512 files per import."
    (some #(not (safe-path? (:path %))) descriptors) "A file has an unsupported relative path."
    (not= (count descriptors) (count (set (map :path descriptors)))) "Selected file paths must be unique."
    (some #(> (:size %) max-file-bytes) descriptors) "Each imported file must be at most 4 MiB."
    (> (reduce + 0 (map :size descriptors)) max-total-bytes) "An import must be at most 32 MiB."))

(def write-lua
  "local path, bytes, first = ...
   assert(not path:match('^/') and not path:find(string.char(92), 1, true), 'Invalid import path')
   for part in path:gmatch('[^/]+') do assert(part ~= '.' and part ~= '..', 'Invalid import path') end
   local full = '/workspace/' .. path
   if first then vim.fn.mkdir(vim.fn.fnamemodify(full, ':h'), 'p') end
   local file = assert(io.open(full, first and 'wb' or 'ab'))
   assert(file:write(vim.base64.decode(bytes)))
   assert(file:close())")

(def open-lua
  "local path = ...; vim.cmd('hide edit ' .. vim.fn.fnameescape('/workspace/' .. path))")

(defn open! [request! path]
  (if (safe-path? path)
    (request! "nvim_exec_lua" [open-lua [path]])
    (a/rejected (js/Error. "Invalid import path"))))

(def ^:private chunk-size 32768)

(defn- write-chunk! [request! path bytes start]
  (let [chunk (.subarray bytes start (+ start chunk-size))
        encoded (js/btoa (.apply (.-fromCharCode js/String) nil chunk))]
    (request! "nvim_exec_lua" [write-lua [path encoded (zero? start)]])))

(defn ^:async write! [request! {:keys [path file]}]
  (let [bytes (js/Uint8Array. (await (.arrayBuffer file)))]
    ;; Even an empty file needs one write to create or truncate it.
    (loop [start 0]
      (let [result (await (write-chunk! request! path bytes start))
            offset (+ start chunk-size)]
        (if (< offset (.-length bytes)) (recur offset) result)))))

(defn ^:async import! [request! file-list folder?]
  (let [describe (fn [file]
                   {:path (if folder? (.-webkitRelativePath file) (.-name file))
                    :size (.-size file)
                    :file file})
        descriptors (mapv describe (array-seq (js/Array.from file-list)))
        paths (mapv :path descriptors)]
    (when-let [message (plan-error descriptors)]
      (throw (js/Error. message)))
    (when-let [first-path (first paths)]
      (doseq [descriptor descriptors] (await (write! request! descriptor)))
      (let [directory (if folder? (first (string/split first-path #"/")) "")]
        (await (request! "nvim_exec_lua"
                         ["local directory = ...; vim.cmd('cd ' .. vim.fn.fnameescape('/workspace/' .. directory))"
                          [directory]])))
      (await (open! request! first-path)))
    paths))
