(ns tolgraven.neovim-fixture
  "Real local WASM component plus pure regression checks; no remote writes."
  (:require [reagent.core :as r]
            [reagent.dom.client :as dom]
            [cljs.test :as test :refer-macros [run-tests]]
            [tolgraven.neovim-test]
            [tolgraven.modules.neovim.views :as views]
            [tolgraven.modules.neovim.runtime :as runtime]))

(defonce *unit (r/atom nil))
(defonce *show? (r/atom true))
(defonce *saved (r/atom ""))
(defonce *live (r/atom "Not run"))
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

(defn <fixture> []
  [:main {:style {:max-width "65rem", :margin "2rem auto", :padding "1rem"}}
   [:h1 "Neovim WASM component"]
   [:p "Press i to insert; Esc returns to normal mode. :help opens bundled help."]
   [:pre {:id "unit-results"} (pr-str @*unit)]
   [:p {:id "worker-count"} (str "Active workers: " @*workers "; created: " @*created)]
   [:button {:on-click #(swap! *show? not)} (if @*show? "Unmount editor" "Mount editor")]
   [:button {:on-click live-checks!} "Run live checks"]
   [:p {:id "live-results"} @*live]
   (when @*show?
     [views/<editor> {:initial-text ";; Neovim WASM\n(defn hello [name]\n  (str \"Hello, \" name))"
                      :filetype "clojure"
                      :on-save #(reset! *saved %)}])
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
