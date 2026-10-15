(ns website.neovim-visual-fixture
  "One real readonly editor per standalone visual document; no provider I/O."
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [website.react :as r]
            [website.test-support :as support]
            [website.modules.neovim.views :as views]
            [website.modules.neovim.runtime :as runtime]))

(def before-text "(ns example)\n(def answer 41)\n")
(def after-text "(ns example)\n(def answer 42)\n(println answer)\n")
(def patch-text "diff --git a/core.clj b/core.clj\n--- a/core.clj\n+++ b/core.clj\n@@ -1,2 +1,3 @@\n (ns example)\n-(def answer 41)\n+(def answer 42)\n+(println answer)\n")

(def documents
  {:patch {:mode :patch
           :files [{:path "commit.patch", :text patch-text}]}
   :diff {:mode :diff
          :files [{:path "before/core.clj", :text before-text}
                  {:path "after/core.clj", :text after-text}]}})

(def readonly-lua
  "local mode = ...
   local wins = vim.api.nvim_list_wins()
   assert(#wins == (mode == 'diff' and 2 or 1), 'Wrong native window count')
   for _, win in ipairs(wins) do
     local buf = vim.api.nvim_win_get_buf(win)
     assert(vim.bo[buf].readonly and not vim.bo[buf].modifiable, 'Editable source buffer')
     assert(vim.wo[win].number and not vim.wo[win].relativenumber, 'Line numbers are not absolute')
     assert(not pcall(vim.api.nvim_buf_set_lines, buf, 0, 1, false, {'changed'}), 'Readonly edit succeeded')
     if mode == 'diff' then assert(vim.wo[win].diff and vim.wo[win].scrollbind, 'Missing native diff options') end
   end
   if mode == 'diff' then
     assert(math.abs(vim.api.nvim_win_get_width(wins[1]) - vim.api.nvim_win_get_width(wins[2])) <= 1, 'Unequal panes')
     assert(vim.fn.diff_hlID(2, 14) ~= 0, 'Missing native diff highlight')
   else assert(vim.bo.filetype == 'diff', 'Patch filetype missing') end
   return true")

(defn bounded [promise]
  (js/Promise.
    (fn [resolve reject]
      (let [timer (js/setTimeout #(reject (js/Error. "Native Neovim visual startup timed out")) 30000)]
        (-> promise
            (.then resolve reject)
            (.finally #(js/clearTimeout timer)))))))

(defn init! []
  (let [mode (keyword (.get (js/URLSearchParams. (.-search js/location)) "visual"))
        unsupported? (= :unsupported mode)
        source-document (get documents (if unsupported? :patch mode))
        _ (assert source-document "Unknown Neovim visual state")
        root (r/create-root (.getElementById js/document "app"))
        original-worker js/Worker
        original-start! runtime/start!
        original-ready (.-ready js/window.visualFixture)
        *workers (atom 0)
        *created (atom 0)
        *owner (atom nil)
        *disposed? (atom false)]
    ;; Observe the actual worker and returned transport, never substitute either.
    (set! js/window.Worker
      (fn [url]
        (let [worker (js/Reflect.construct original-worker #js [url])
              terminate (.-terminate worker)
              *stopped? (atom false)]
          (swap! *workers inc)
          (swap! *created inc)
          (set! (.-terminate worker)
            (fn []
              (when (compare-and-set! *stopped? false true)
                (swap! *workers dec)
                (.call terminate worker))))
          worker)))
    (set! runtime/start!
      (fn [& args]
        (assert (nil? @*owner) "Only one WASM session is allowed per visual fixture")
        (let [owner (apply original-start! args)]
          (reset! *owner owner)
          owner)))
    (aset js/window.visualFixture "dispose"
      (fn []
        (when (compare-and-set! *disposed? false true)
          (r/unmount root)
          (set! js/window.Worker original-worker)
          (set! runtime/start! original-start!))
        (support/wait-for! #(zero? @*workers) "Unmount did not terminate the owned Neovim worker")))
    (aset js/window.visualFixture "ready"
      (fn []
        (go-promise
          (if unsupported?
            (do
              (assert (not (runtime/available?)) "Unsupported fixture unexpectedly has WASM capabilities")
              (await! (support/wait-for!
                        #(= (runtime/unavailable-message)
                            (some-> (.querySelector js/document ".neovim__toolbar [role=status]") .-textContent))
                        "Native unsupported state did not commit"))
              (assert (zero? @*created) "Unsupported editor created a worker"))
            (do
              (assert (and (= js/window js/window.top) (runtime/available?)) "Readonly visual cases require isolated top-level WASM")
              (await! (support/wait-for! #(some? @*owner) "Native editor did not start"))
              (let [session (await! (bounded (:ready @*owner)))]
                (assert session "Native startup returned no session")
                (await! (runtime/request! session "nvim_exec_lua" [readonly-lua [(name mode)]]))
                (doseq [{:keys [path text]} (:files source-document)]
                  (let [lines (await! (runtime/request! session "nvim_exec_lua"
                                       ["local path = ...; return vim.api.nvim_buf_get_lines(vim.fn.bufnr('/workspace/' .. path), 0, -1, false)" [path]]))]
                    (assert (= (.join lines "\n") (.replace text #"\n$" "")) "Native buffer differs from local source")))
                (assert (= 1 @*created @*workers) "Readonly fixture must retain exactly one real worker"))
              (await! (support/wait-for!
                        #(when-let [grid (.querySelector js/document ".neovim__grid")]
                           (and (= "visible" (.-visibility (js/getComputedStyle grid)))
                                (.includes (.-textContent grid) "answer")
                                (not (.querySelector js/document ".neovim__progress"))))
                        "Configured readonly text did not paint"))))
          (assert (= "true" (.getAttribute (.querySelector js/document ".neovim__grid") "aria-readonly")) "Native readonly accessibility contract missing")
          (await! (original-ready)))))
    (r/render root
      [:main#main.main-content
       ;; Same direct parent as the public readonly GitHub viewer, without its
       ;; remote query/controller. Neovim owns the real buffers, rows and menu.
       [:section.github-diff
        [views/<editor> {:document source-document, :rows 36}]]])))
