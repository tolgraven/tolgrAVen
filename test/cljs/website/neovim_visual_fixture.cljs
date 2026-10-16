(ns website.neovim-visual-fixture
  "One real editable or readonly editor per isolated visual document; no provider I/O."
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [website.react :as r]
            [website.component]
            [website.subs]
            [website.macros :refer-macros [defpage]]
            [website.test-support :as support]
            [website.modules.neovim.views :as views]
            [website.modules.neovim.runtime :as runtime]
            [website.modules.neovim.profile :as profile]
            [website.modules.neovim.files :as files]))

(def before-text "(ns example)\n(def answer 41)\n")
(def after-text "(ns example)\n(def answer 42)\n(println answer)\n")
(def patch-text "diff --git a/core.clj b/core.clj\n--- a/core.clj\n+++ b/core.clj\n@@ -1,2 +1,3 @@\n (ns example)\n-(def answer 41)\n+(def answer 42)\n+(println answer)\n")

(def documents
  {:patch {:mode :patch
           :files [{:path "commit.patch", :text patch-text}]}
   :diff {:mode :diff
          :files [{:path "before/core.clj", :text before-text}
                  {:path "after/core.clj", :text after-text}]}})

(def workspace-file "test/visual/core.clj")

(def workspace-files
  [{:path workspace-file
    :text (str "(ns visual.workspace)\n\n"
               "(def answer 42)\n"
               "(defn greeting [name] (str \"Hello, \" name \" λ\"))\n"
               "(println (greeting \"native-workspace\"))\n"
               (apply str (for [line (range 6 65)]
                            (str ";; Local workspace line " line "\n"))))}
   {:path "test/visual/theme.scss"
    :text ".native-workspace {\n  color: #a6b58a;\n}\n"}])

(def workspace-lua
  "local path, expected = ...
   assert(table.concat(vim.api.nvim_buf_get_lines(0, 0, -1, false), '\\n') == expected:gsub('\\n$', ''), 'Native initial buffer differs from fixture source')
   assert(vim.fn.getcwd() == '/workspace/tolgrAVen', 'Native repository cwd missing')
   assert(vim.api.nvim_buf_get_name(0) == '/workspace/tolgrAVen/' .. path, 'Wrong native initial file')
   assert(vim.bo.modifiable and not vim.bo.readonly and vim.bo.filetype == 'clojure', 'Workspace is not editable Clojure')
   assert(not vim.bo.modified, 'Initial workspace file is already modified')
   assert(vim.g.colors_name == 'bruvbox', 'Native Bruvbox profile missing')
   local normal = vim.api.nvim_get_hl(0, {name = 'Normal', link = false})
   assert(normal.fg and normal.bg and normal.fg ~= normal.bg, 'Native RGB palette missing')
   assert(vim.fn.exists(':Meta') == 2 and package.loaded.metabuffer, 'Native Meta setup missing')
   assert(vim.fn.exists(':NERDTree') == 2 and vim.fn.WebDevIconsGetFileTypeSymbol('core.clj') ~= '', 'Native tree/devicons missing')
   assert(vim.g.loaded_airline == 1 and vim.g.airline_theme == 'bruvbox', 'Native airline profile missing')
   assert(vim.fn.exists(':Sayonara') == 2 and vim.fn.exists(':RainbowParentheses') == 2, 'Own plugins missing')
   local mapping = vim.fn.maparg(',o', 'n', false, true)
   assert(mapping.buffer == 1 and mapping.rhs:find('sexp_raise_list', 1, true), 'Custom Clojure mapping missing')
   assert(vim.o.mouse == 'a', 'Native mouse input is not enabled')
   assert(vim.g.loaded_diminactive == 1 and vim.g.diminactive_use_colorcolumn == 1, 'Native inactive-window profile missing')
   assert(vim.g.colorcolumn_orig:match('^80,81,82,') and vim.g.colorcolumn_orig:match(',240$'), 'Native color columns missing')
   assert(vim.api.nvim_get_hl(0, {name = 'Cursor', link = false}).reverse, 'Native block cursor is not reversed')
   local cursor = vim.api.nvim_win_get_cursor(0)
   assert(cursor[1] == 1 and cursor[2] == 0 and vim.api.nvim_get_mode().mode == 'n', 'Initial native cursor moved')
   return true")

(defn seed-workspace! [request!]
  ;; Seed only this fixture's tiny files after the real profile, before the real
  ;; runtime opens initial-file and attaches the UI. Keep the bundled repository.
  (reduce (fn [pending {:keys [path text]}]
            (.then pending
              #(files/write! request! {:path (str "tolgrAVen/" path)
                                      :file (js/Blob. #js [text])})))
          (js/Promise.resolve nil) workspace-files))

(defn observe-first-workspace-paint! [*first-paint]
  (let [observer (js/MutationObserver.
                   (fn [_ observer]
                     (when-let [grid (.querySelector js/document ".neovim__grid")]
                       (when (= "visible" (.-visibility (js/getComputedStyle grid)))
                         (reset! *first-paint
                           (and (.includes (.-textContent grid) "core.clj")
                                (.includes (.-textContent grid) "(ns visual.workspace)")
                                (not (.querySelector js/document ".neovim__progress"))))
                         (.disconnect observer)))))]
    (.observe observer (.getElementById js/document "app")
              #js {:subtree true, :childList true, :attributes true})
    observer))

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

(defpage <readonly-page> [source-document]
  [:section.github-diff
   [views/<editor> {:document source-document, :rows 36}]])

(defpage <workspace-page> []
  [:section.neovim-page
   [views/<editor> {:initial-file workspace-file
                    :filetype "clojure"
                    :rows 36
                    :menu-content [:a {:href "/", :data-document-navigation "true"} "Back to site"]}]])

(defn init! []
  (let [mode (keyword (.get (js/URLSearchParams. (.-search js/location)) "visual"))
        unsupported? (= :unsupported mode)
        workspace? (= :workspace mode)
        source-document (get documents (if unsupported? :patch mode))
        _ (assert (or workspace? source-document) "Unknown Neovim visual state")
        root (r/create-root (.getElementById js/document "app"))
        original-worker js/Worker
        original-start! runtime/start!
        original-configure! profile/configure!
        original-ready (.-ready js/window.visualFixture)
        *workers (atom 0)
        *created (atom 0)
        *owner (atom nil)
        *disposed? (atom false)
        *first-paint (atom nil)
        paint-observer (when workspace? (observe-first-workspace-paint! *first-paint))]
    (when workspace?
      (set! profile/configure!
        (fn [request!]
          (-> (original-configure! request!)
              (.then #(seed-workspace! request!))))))
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
    (when workspace?
      ;; Read-only inspection for separate trusted keyboard/mouse checks. This
      ;; returns the actual owned process state and never injects input or buffers.
      (aset js/window.visualFixture "workspaceState"
        (fn []
          (go-promise
            (let [session (await! (bounded (:ready @*owner)))
                  state (await! (runtime/request! session "nvim_exec_lua"
                                  ["return {path = vim.api.nvim_buf_get_name(0), lines = vim.api.nvim_buf_get_lines(0, 0, -1, false), cursor = vim.api.nvim_win_get_cursor(0), mode = vim.api.nvim_get_mode().mode, top = vim.fn.line('w0'), modified = vim.bo.modified}" []]))]
              (aset state "workers" @*workers)
              (aset state "created" @*created)
              state)))))
    (aset js/window.visualFixture "dispose"
      (fn []
        (when (compare-and-set! *disposed? false true)
          (r/unmount root)
          (when paint-observer (.disconnect paint-observer))
          (set! js/window.Worker original-worker)
          (set! runtime/start! original-start!)
          (set! profile/configure! original-configure!))
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
              (assert (and (= js/window js/window.top) (runtime/available?)) "Native visual cases require isolated top-level WASM")
              (await! (support/wait-for! #(some? @*owner) "Native editor did not start"))
              (let [session (await! (bounded (:ready @*owner)))]
                (assert session "Native startup returned no session")
                (await! (runtime/request! session "nvim_exec_lua"
                          [(if workspace? workspace-lua readonly-lua)
                           (if workspace? [workspace-file (:text (first workspace-files))]
                                          [(name mode)])]))
                (doseq [{:keys [path text]} (if workspace?
                                            (map #(update % :path (partial str "tolgrAVen/")) workspace-files)
                                            (:files source-document))]
                  (let [lines (await! (runtime/request! session "nvim_exec_lua"
                                       [(if workspace?
                                          "local path = ...; return vim.fn.readfile('/workspace/' .. path)"
                                          "local path = ...; return vim.api.nvim_buf_get_lines(vim.fn.bufnr('/workspace/' .. path), 0, -1, false)") [path]]))]
                    (assert (= (.join lines "\n") (.replace text #"\n$" "")) "Native file differs from local source")))
                (assert (= 1 @*created @*workers) "Native fixture must retain exactly one real worker"))
              (await! (support/wait-for!
                        #(when-let [grid (.querySelector js/document ".neovim__grid")]
                           (and (= "visible" (.-visibility (js/getComputedStyle grid)))
                                (.includes (.-textContent grid) (if workspace? "(ns visual.workspace)" "answer"))
                                (not (.querySelector js/document ".neovim__progress"))))
                        "Configured native text did not paint"))))
          (assert (= (if workspace? "false" "true")
                     (.getAttribute (.querySelector js/document ".neovim__grid") "aria-readonly"))
                  "Native editor accessibility contract missing")
          (when workspace?
            (assert (true? @*first-paint) "Intermediate startup buffer flashed before the configured file")
            (assert (.querySelector js/document ".neovim__grid--mouse .neovim__cursor--block")
                    "Native editable mouse/block cursor did not paint")
            (assert (= "(" (.-textContent (.querySelector js/document ".neovim__cursor--block")))
                    "Block cursor lost the character underneath it")
            (assert (not (.querySelector js/document "header, footer, .sticky-footer, .back-to-top"))
                    "Standalone workspace unexpectedly contains site chrome"))
          (await! (original-ready))
          (assert (.querySelector js/document (if workspace?
                                                "#main > .page-root > .neovim-page"
                                                "#main > .page-root > .github-diff"))
                  "Native editor page container missing")
          (doseq [selector [".neovim" ".neovim__toolbar > .disclosure-menu > summary"]]
            (let [control (.querySelector js/document selector)
                  bounds (when control (.getBoundingClientRect control))]
              (assert (and bounds (<= 0 (.-left bounds)) (<= (.-right bounds) js/window.innerWidth))
                      (str "Native surface or menu exceeds the viewport: " selector)))))))
    (r/render root
      ;; Public Neovim routes omit shell chrome. Keep both its native main and
      ;; defpage container: grid intrinsic sizing depends on the page owner.
      [:main#main.main-content.perspective-top.main-content--bare
       (if workspace? [<workspace-page>] [<readonly-page> source-document])])))
