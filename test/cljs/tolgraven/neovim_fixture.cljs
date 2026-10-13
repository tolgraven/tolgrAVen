(ns tolgraven.neovim-fixture
  "Real local WASM component plus pure regression checks; no remote writes."
  (:require [reagent.core :as r]
            [reagent.dom.client :as dom]
            [cljs.test :as test :refer-macros [run-tests]]
            [tolgraven.neovim-test]
            [tolgraven.modules.neovim.views :as views]
            [tolgraven.modules.neovim.runtime :as runtime]
            [tolgraven.modules.neovim.files :as files]))

(defonce *unit (r/atom nil))
(defonce *show? (r/atom true))
(defonce *alternate? (r/atom false))
(defonce *saved (r/atom ""))
(defonce *live (r/atom "Not run"))
(defonce *plugins (r/atom "Not run"))
(defonce *workspace (r/atom "Not run"))
(defonce *startup-ms (r/atom nil))
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

(defn <fixture> []
  [:main {:style {:max-width "65rem", :margin "2rem auto", :padding "1rem"}}
   [:h1 "Neovim WASM component"]
   [:p "Press i to insert; Esc returns to normal mode. :help opens bundled help."]
   [:pre {:id "unit-results"} (pr-str @*unit)]
   [:p {:id "worker-count"} (str "Active workers: " @*workers "; created: " @*created)]
   [:button {:on-click #(swap! *show? not)} (if @*show? "Unmount editor" "Mount editor")]
   [:button {:on-click #(swap! *alternate? not)}
    (if @*alternate? "Restore initializer" "Change initializer")]
   [:button {:on-click live-checks!} "Run live checks"]
   [:p {:id "live-results"} @*live]
   [:button {:on-click plugin-checks!} "Run plugin checks"]
   [:p {:id "plugin-results"} @*plugins]
   [:button {:on-click workspace-checks!} "Run workspace checks"]
   [:p {:id "workspace-results"} @*workspace]
   (when @*startup-ms [:p {:id "startup-ms"} (str "Whole worker startup: " @*startup-ms " ms")])
   (when @*show?
     [views/<editor> (merge {:filetype "clojure"
                            :on-save #(reset! *saved %)}
                           (if @*alternate?
                             {:initial-text ";; New session"}
                             {:initial-file "src/frontend/tolgraven/modules/neovim/views.cljs"}))])
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
  (run-tests 'tolgraven.neovim-test)
  (dom/render (dom/create-root (.getElementById js/document "app")) [<fixture>]))
