(ns tolgraven.neovim-fixture
  "Real local WASM component plus pure regression checks; no remote writes."
  (:require [reagent.core :as r]
            [reagent.dom.client :as dom]
            [cljs.test :as test :refer-macros [run-tests]]
            [tolgraven.neovim-test]
            [tolgraven.modules.neovim.views :as views]
            [tolgraven.modules.neovim.runtime :as runtime]
            [tolgraven.modules.neovim.files :as files]
            [tolgraven.modules.neovim.document :as document]))

(defonce *unit (r/atom nil))
(defonce *show? (r/atom true))
(defonce *alternate? (r/atom false))
(defonce *saved (r/atom ""))
(defonce *live (r/atom "Not run"))
(defonce *plugins (r/atom "Not run"))
(defonce *workspace (r/atom "Not run"))
(defonce *diff (r/atom "Not run"))
(defonce *diff-view? (r/atom false))
(defonce *settings (r/atom "Not run"))
(defonce *startup-ms (r/atom nil))
(defonce *first-frame (r/atom "Waiting for first visible frame…"))
(defonce *workers (r/atom 0))
(defonce *created (r/atom 0))

(defmethod test/report [::test/default :end-run-tests] [summary] (reset! *unit summary))

(defn live-checks! []
  (reset! *live "Running…")
  (let [{:keys [ready stop!]}
        (runtime/start! {:cols 40, :rows 12, :initial-text "hello", :filetype "clojure"}
                        (fn [_]) (fn [_ _]))]
    (-> ready
        (.then (fn [session]
                 (when-not session (throw (js/Error. "WASM startup failed")))
                 (-> (runtime/request! session "nvim_get_current_line" [])
                     (.then (fn [line]
                              (assert (= "hello" line))
                              (runtime/request! session "nvim_exec_lua" ["return 6 * 7" []])))
                     (.then (fn [result]
                              (assert (= 42 result))
                              (runtime/request! session "nvim_command" ["help usr_01"])))
                     (.then (fn [_] (runtime/request! session "nvim_buf_get_name" [0])))
                     (.then (fn [name]
                              (assert (.endsWith name "usr_01.txt"))
                              (reset! *live "PASS: real RPC, buffer, Lua and bundled help"))))))
        (.catch #(reset! *live (str "FAIL: " (.-message %))))
        (.finally stop!))))

(def plugin-check-lua
  "assert(vim.g.colors_name == 'bruvbox', 'Bruvbox is not active')
   local normal = vim.api.nvim_get_hl(0, {name = 'Normal', link = false})
   assert(normal.fg and normal.bg, 'Bruvbox RGB palette is missing')
   assert(vim.fn.exists(':Meta') == 2, 'Meta is not installed')
   vim.fn.mkdir('/workspace', 'p')
   vim.fn.writefile({'hello first', 'unrelated', 'hello last'}, '/workspace/plugin-check.txt')
   vim.cmd('hide edit /workspace/plugin-check.txt')
   local source = vim.api.nvim_get_current_buf()
   vim.cmd('Meta hello')
   local router = require('metabuffer.router')
   local session = router['active-by-source'][source]
   assert(session, 'Meta session did not start')
   local results = session.meta.buf.buffer
   assert(vim.wait(2000, function()
     local lines = vim.api.nvim_buf_get_lines(results, 0, -1, false)
     return #lines == 2 and lines[1] == 'hello first' and lines[2] == 'hello last'
   end, 20), 'Meta did not filter the live buffer')
   router['enter-edit-mode'](session['prompt-buf'])
   vim.api.nvim_buf_set_lines(results, 0, 1, false, {'edited hello first'})
   vim.cmd('write')
   router.cancel(session['prompt-buf'])
   local restored = vim.api.nvim_buf_get_lines(source, 0, -1, false)
   assert(restored[1] == 'edited hello first' and restored[2] == 'unrelated' and restored[3] == 'hello last',
     'Meta writeback did not preserve the unfiltered line: ' .. vim.inspect(restored))
   assert(session['ui-hidden'] and vim.api.nvim_get_current_buf() == source, 'Meta did not restore the source view')
   vim.cmd('setlocal filetype=clojure')
   local mapping = vim.fn.maparg(',o', 'n', false, true)
   assert(mapping.buffer == 1 and mapping.rhs:find('sexp_raise_list', 1, true), 'Custom sexp mapping missing')
   assert(vim.fn.exists(':Sayonara') == 2 and vim.fn.exists(':RainbowParentheses') == 2, 'Own plugins missing')
   assert(vim.g.loaded_airline == 1, 'Airline missing')
   vim.cmd('NERDTree /workspace/tolgrAVen')
   local tree = table.concat(vim.api.nvim_buf_get_lines(0, 0, -1, false), '\\n')
   assert(tree:find('src', 1, true) and tree:find('resources', 1, true), 'NERDTree did not browse the repository')
   assert(vim.fn.WebDevIconsGetFileTypeSymbol('test.clj') ~= '', 'Devicons missing')
   vim.cmd('NERDTreeClose')
   return 'PASS: Bruvbox, Meta filter/writeback, NERDTree, devicons, airline and custom sexp mappings'")

(defn plugin-checks! []
  (reset! *plugins "Running…")
  (let [started (js/performance.now)
        {:keys [ready stop!]}
        (runtime/start! {:cols 80, :rows 24, :initial-text "hello", :filetype "text"}
                        (fn [_]) (fn [_ _]))]
    (-> ready
        (.then (fn [session]
                 (when-not session (throw (js/Error. "Plugin bootstrap failed")))
                 (reset! *startup-ms (js/Math.round (- (js/performance.now) started)))
                 (runtime/request! session "nvim_exec_lua" [plugin-check-lua []])))
        (.then #(reset! *plugins %))
        (.catch #(reset! *plugins (str "FAIL: " (.-message %))))
        (.finally stop!))))

(def workspace-check-lua
  "assert(vim.fn.getcwd() == '/workspace/tolgrAVen', 'Repository cwd missing')
   assert(vim.fn.filereadable('src/frontend/tolgraven/modules/neovim/views.cljs') == 1, 'Repository source missing')
   assert(vim.fn.filereadable('resources/scss/modules/neovim.scss') == 1, 'Repository SCSS missing')
   local source = vim.api.nvim_get_current_buf()
   vim.cmd('Meta! #file')
   local router = require('metabuffer.router')
   local session = router['active-by-source'][source]
   assert(session, 'Repository Meta session missing')
   assert(vim.wait(10000, function()
     local text = table.concat(vim.api.nvim_buf_get_lines(session.meta.buf.buffer, 0, -1, false), ' ')
     return text:find('.cljs', 1, true) and text:find('.scss', 1, true)
   end, 25), 'Meta did not discover code and SCSS')
   router.cancel(session['prompt-buf'])
   return true")

(defn workspace-checks! []
  (reset! *workspace "Running…")
  (let [{:keys [ready stop!]}
        (runtime/start! {:cols 100, :rows 24, :initial-text "hello", :filetype "text"}
                        (fn [_]) (fn [_ _]))
        bytes (js/Uint8Array. 32769)
        text-file (js/File. #js ["(println \"hello λ\")\n"] "core.clj")
        binary-file (js/File. #js [bytes] "binary.dat")
        empty-file (js/File. #js [] "empty.txt")]
    (js/Object.defineProperty text-file "webkitRelativePath" #js {:value "fixture/src/core.clj"})
    (js/Object.defineProperty binary-file "webkitRelativePath" #js {:value "fixture/binary.dat"})
    (js/Object.defineProperty empty-file "webkitRelativePath" #js {:value "fixture/empty.txt"})
    (-> ready
        (.then (fn [session]
                 (when-not session (throw (js/Error. "Workspace startup failed")))
                 (-> (runtime/request! session "nvim_exec_lua" [workspace-check-lua []])
                     (.then #(files/import! (fn [method params] (runtime/request! session method params))
                               #js [text-file binary-file empty-file] true))
                     (.then (fn [paths]
                              (assert (= 3 (count paths)))
                              (runtime/request! session "nvim_exec_lua"
                                ["assert(vim.fn.getcwd() == '/workspace/fixture'); local f = assert(io.open('/workspace/fixture/binary.dat', 'rb')); local bytes = f:read('*a'); f:close(); assert(#bytes == 32769 and bytes == string.rep(string.char(0), 32769)); assert(vim.fn.getfsize('/workspace/fixture/empty.txt') == 0); return vim.api.nvim_get_current_line()" []])))
                     (.then (fn [line]
                              (assert (= "(println \"hello λ\")" line))
                              (reset! *workspace "PASS: Meta repository discovery; folder paths, Unicode, empty and multi-packet binary imports"))))))
        (.catch #(reset! *workspace (str "FAIL: " (.-message %))))
        (.finally stop!))))

(def settings-check-lua
  "local expected = {
     number = true, numberwidth = 3, showmatch = true, matchtime = 3, cursorline = true,
     showtabline = 2, showmode = false, shortmess = 'atTcA', foldminlines = 2,
     foldlevelstart = 1, foldcolumn = '0', list = true,
     ttimeout = true, ttimeoutlen = 10, timeoutlen = 370, updatetime = 50, redrawtime = 4000,
     lazyredraw = true, maxmempattern = 10000, shiftround = true, tabstop = 2, shiftwidth = 2,
     expandtab = true, smarttab = true, textwidth = 0, belloff = 'mess,spell,wildmode',
     title = true, titlelen = 60, titlestring = '', wrap = false, whichwrap = '<,>,[,],b,s',
     linebreak = true, ignorecase = true, smartcase = true, startofline = false,
     autoindent = true, virtualedit = 'block,onemore', wrapscan = true, incsearch = true,
     gdefault = true, formatoptions = 'jcro,ql', undofile = true, undolevels = 20000,
     swapfile = false, autowrite = true, confirm = true, fileformats = 'unix,dos,mac', hidden = true,
     synmaxcol = 15000, conceallevel = 2, concealcursor = 'nv', signcolumn = 'auto:1-4',
     splitbelow = true, splitright = true, switchbuf = 'usetab', scrolloff = 3, sidescrolloff = 2,
     sidescroll = 1, winminwidth = 0, winheight = 7, winminheight = 4, previewheight = 25,
     equalalways = true, eadirection = 'ver', cmdwinheight = 20,
     mouse = 'a', mousemodel = 'popup_setpos', mousescroll = 'ver:1,hor:1',
     showcmd = false, inccommand = 'split', pumblend = 20,
   }
   for name, value in pairs(expected) do
     local actual = vim.api.nvim_get_option_value(name, {scope = 'global'})
     assert(actual == value, name .. ': expected ' .. tostring(value) .. ', got ' .. tostring(actual))
   end
   assert(vim.o.fillchars == 'fold: ,vert:│' and vim.o.listchars == 'tab:܁ ,trail:·,eol:↵', 'Display characters missing')
   assert(vim.o.foldopen:find('jump', 1, true), 'Fold-open jump missing')
   assert(vim.o.sessionoptions:find('globals', 1, true) and not vim.o.sessionoptions:find('blank', 1, true), 'Session options missing')
   assert(vim.fn.isdirectory(vim.o.undodir) == 1, 'Virtual undo directory missing')
   vim.api.nvim_buf_set_lines(0, 0, -1, false, {'(settings)'})
   local rainbow = vim.tbl_map(function(id) return vim.fn.synIDattr(id, 'name') end, vim.fn.synstack(1, 1))
   assert(table.concat(rainbow, ' '):find('rainbowParens', 1, true), 'Clojure rainbow syntax missing')
   local columns = vim.g.colorcolumn_orig
   assert(columns:match('^80,81,82,') and columns:match(',240$'), 'Color columns missing')
   assert(vim.g.loaded_diminactive == 1 and vim.g.diminactive == 1 and vim.g.diminactive_use_colorcolumn == 1, 'Diminactive missing')
   local source = vim.api.nvim_get_current_win()
   vim.cmd('vsplit')
   local second = vim.api.nvim_get_current_win()
   assert(vim.api.nvim_get_option_value('colorcolumn', {win = source}):match('^1,2,3,'), 'Inactive split was not dimmed')
   assert(vim.api.nvim_get_option_value('colorcolumn', {win = second}) == columns, 'Active split lost color columns')
   vim.api.nvim_set_current_win(source)
   assert(vim.wo.colorcolumn == columns, 'Focus did not restore source color columns')
   assert(vim.api.nvim_get_option_value('colorcolumn', {win = second}):match('^1,2,3,'), 'Previous active split was not dimmed')
   vim.cmd('only')
   vim.cmd('NERDTree /workspace/tolgrAVen')
   assert(vim.bo.filetype == 'nerdtree' and vim.wo.conceallevel == 3, 'Tree conceal setup missing')
   local found = false
   for line, text in ipairs(vim.api.nvim_buf_get_lines(0, 0, -1, false)) do
     if text:find('resources/', 1, true) then
       local first, last = text:find('[', 1, true), text:find(']', 1, true)
       assert(first and last and vim.fn.synconcealed(line, first)[1] == 1 and vim.fn.synconcealed(line, last)[1] == 1,
         'Devicon brackets are not concealed after syntax loading: ' .. vim.inspect({text = text, first = first, last = last, syntax = vim.bo.syntax, concealed = vim.fn.synconcealed(line, first), stack = vim.tbl_map(function(id) return vim.fn.synIDattr(id, 'name') end, vim.fn.synstack(line, first)), rules = vim.fn.execute('syntax list hideBracketsInNerdTree')}))
       found = true
     end
   end
   assert(found, 'Tree fixture directory missing')
   vim.cmd('NERDTreeClose')
   return 'PASS: local options, mouse settings, split dimming, color-column restoration and concealed devicon brackets'")

(defn settings-checks! []
  (reset! *settings "Running…")
  (let [{:keys [ready stop!]}
        (runtime/start! {:cols 120, :rows 24, :initial-text "settings", :filetype "clojure"}
                        (fn [_]) (fn [_ _]))]
    (-> ready
        (.then (fn [session]
                 (when-not session (throw (js/Error. "Settings startup failed")))
                 (runtime/request! session "nvim_exec_lua" [settings-check-lua []])))
        (.then (fn [result]
                 (-> ready
                     (.then (fn [session]
                              (-> (runtime/request! session "nvim_ui_set_focus" [false])
                                  (.then #(runtime/request! session "nvim_exec_lua"
                                            ["assert(vim.wait(1000, function() return vim.g.diminactive == 0 end, 10), 'FocusLost did not disable dimming')" []]))
                                  (.then #(runtime/request! session "nvim_ui_set_focus" [true]))
                                  (.then #(runtime/request! session "nvim_exec_lua"
                                            ["assert(vim.wait(1000, function() return vim.g.diminactive == 1 end, 10), 'FocusGained did not restore dimming'); assert(vim.wo.colorcolumn == vim.g.colorcolumn_orig, 'Focus color columns were not restored')" []]))
                                  (.then #(reset! *settings (str result "; native focus restoration")))))))))
        (.catch #(reset! *settings (str "FAIL: " (.-message %))))
        (.finally stop!))))

(def sample-diff
  {:mode :diff
   :files [{:path "before/core.clj", :text "(ns example)\n(def answer 41)\n"}
           {:path "after/core.clj", :text "(ns example)\n(def answer 42)\n(println answer)\n"}]})

(def large-diff
  ;; The first 32 KiB boundary splits a UTF-8 character. This catches bulk
  ;; source startup failures that a short initial buffer cannot exercise.
  (update sample-diff :files
          #(mapv (fn [file]
                   (update file :text str "\n\n\n\n\n"
                           (apply str (repeat 4096 ";; λ retained\n")))) %)))

(defn diff-checks! []
  (reset! *diff "Running…")
  (let [{:keys [ready stop!] transport-session :session}
        (runtime/start! {:cols 100, :rows 20, :document large-diff}
                        (fn [_]) (fn [status message]
                                   (when-not (= :ready status) (reset! *diff message))))]
    (-> ready
        (.then (fn [session]
                 (when-not session (throw (js/Error. "Diff startup failed")))
                 (runtime/request! session "nvim_exec_lua"
                   ["local wins = vim.api.nvim_list_wins(); assert(#wins == 2)
                     assert(math.abs(vim.api.nvim_win_get_width(wins[1]) - vim.api.nvim_win_get_width(wins[2])) <= 1, 'Diff panes are unequal')
                     for _, win in ipairs(wins) do
                       local buf = vim.api.nvim_win_get_buf(win)
                       assert(not vim.bo[buf].modifiable and vim.bo[buf].readonly)
                       assert(vim.wo[win].diff and vim.wo[win].scrollbind and vim.wo[win].number)
                       assert(not vim.wo[win].relativenumber)
                       assert(not pcall(vim.api.nvim_buf_set_lines, buf, 0, 1, false, {'changed'}))
                     end
                     assert(vim.fn.diff_hlID(2, 14) ~= 0, 'Native diff highlighting missing')
                     local f = assert(io.open('/workspace/before/core.clj', 'rb'))
                     assert(vim.fn.sha256(f:read('*a')) == '8b9c16dd4d3e76ae000de41bd045b613abc273b65a28ff48e20be0be49f5bd7e', 'Bulk UTF-8 source bytes changed'); f:close()
                     return true" []])))
        (.then (fn [_]
                 (-> ready (.then (fn [session]
                   (-> (document/open! #(runtime/request! session %1 %2)
                         {:mode :diff
                          :files [{:path "before/added.txt", :text ""}
                                  {:path "after/added.txt", :text (apply str (repeat 256 "added line\n"))}]})
                       (.then #(runtime/request! session "nvim_exec_lua"
                                 ["local wins = vim.api.nvim_list_wins(); assert(#wins == 2); assert(vim.fn.getwininfo(wins[2])[1].topline == 1, 'Added source inherited the previous viewport'); return true" []]))))))))
        (.then (fn [_]
                 (-> ready (.then (fn [session]
                   (-> (document/open! #(runtime/request! session %1 %2)
                         {:mode :patch, :files [{:path "commit.patch", :text "@@ -1 +1 @@\n-old\n+new\n"}]})
                       (.then #(runtime/request! session "nvim_exec_lua"
                                 ["assert(#vim.api.nvim_list_wins() == 1); assert(vim.bo.filetype == 'diff'); assert(vim.bo.readonly and not vim.bo.modifiable); return 'PASS: native split diff, absolute line numbers, immutable buffers, equal panes, bulk UTF-8 file bytes and patch view'" []]))))))))
        (.then #(reset! *diff %))
        (.catch #(reset! *diff (str "FAIL: " (.-message %) "; unread input: " (.-pendingInputBytes (.diagnostics transport-session)))))
        (.finally stop!))))

(defn <fixture> []
  [:main {:style {:max-width "65rem", :margin "2rem auto", :padding "1rem"}}
   [:h1 "Neovim WASM component"]
   [:button {:on-click #(swap! *diff-view? not)} "Toggle diff preview"]
   [:button {:disabled (not (runtime/available?)), :on-click diff-checks!} "Run diff checks"]
   [:p {:id "diff-results"} @*diff]
   [:p "Press i to insert; Esc returns to normal mode. :help opens bundled help."]
   [:pre {:id "unit-results"} (pr-str @*unit)]
   [:p {:id "first-frame"} @*first-frame]
   [:p {:id "worker-count"} (str "Active workers: " @*workers "; created: " @*created)]
   [:button {:on-click #(swap! *show? not)} (if @*show? "Unmount editor" "Mount editor")]
   [:button {:on-click #(swap! *alternate? not)}
    (if @*alternate? "Restore initializer" "Change initializer")]
   [:button {:disabled (not (runtime/available?)), :on-click live-checks!} "Run live checks"]
   [:p {:id "live-results"} @*live]
   [:button {:disabled (not (runtime/available?)), :on-click plugin-checks!} "Run plugin checks"]
   [:p {:id "plugin-results"} @*plugins]
   [:button {:disabled (not (runtime/available?)), :on-click settings-checks!} "Run settings checks"]
   [:p {:id "settings-results"} @*settings]
   [:button {:disabled (not (runtime/available?)), :on-click workspace-checks!} "Run workspace checks"]
   [:p {:id "workspace-results"} @*workspace]
   (when @*startup-ms [:p {:id "startup-ms"} (str "Whole worker startup: " @*startup-ms " ms")])
   (when @*show?
     [views/<editor> (merge {:filetype "clojure"
                            :on-save #(reset! *saved %)}
                           (if @*diff-view?
                             {:document sample-diff}
                             (if @*alternate?
                             {:initial-text ";; New session"}
                             {:initial-file "src/frontend/tolgraven/modules/neovim/views.cljs"})))])
   [:pre {:id "saved-buffer"} @*saved]])

(defn ^:export init! []
  ;; Fixture-only counters verify exact component ownership without a fake runtime.
  (let [original js/Worker]
    (set! js/window.Worker
          (fn [url]
            (let [worker (js/Reflect.construct original #js [url])
                  terminate (.-terminate worker)
                  *stopped? (atom false)]
              (swap! *created inc)
              (swap! *workers inc)
              (set! (.-terminate worker)
                    (fn []
                      (when (compare-and-set! *stopped? false true)
                        (swap! *workers dec)
                        (.call terminate worker))))
              worker))))
  ;; Fixture-only observation: inspect the first visible grid, then disconnect.
  (if-not (runtime/available?)
    (reset! *first-frame "Skipped: this browser context cannot start the WASM editor")
    (let [observer (js/MutationObserver.
                   (fn [_ observer]
                     (when-let [grid (.querySelector js/document ".neovim__grid")]
                       (when (= "visible" (.-visibility (js/getComputedStyle grid)))
                         (let [rows (array-seq (.querySelectorAll grid ".neovim__row"))
                               title (.-textContent (first rows))
                               line (.-textContent (second rows))
                               loading? (.querySelector js/document ".neovim__progress")]
                           (reset! *first-frame
                             (if (and (not loading?) (.includes title "views.cljs")
                                      (.includes line "(ns tolgraven.modules.neovim.views"))
                               "PASS: first visible grid contains the configured source file, after loading"
                               "FAIL: an intermediate startup grid became visible")))
                         (.disconnect observer)))))]
    (.observe observer (.getElementById js/document "app")
              #js {:subtree true, :childList true, :attributes true})))
  (run-tests 'tolgraven.neovim-test)
  (dom/render (dom/create-root (.getElementById js/document "app")) [<fixture>]))
