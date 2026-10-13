(ns tolgraven.github-diff-fixture
  "Live public GitHub reads and the real native diff component; no provider writes."
  (:require [reagent.core :as r]
            [reagent.dom.client :as dom]
            [cljs.test :as test :refer-macros [run-tests]]
            [tolgraven.github-diff-test]
            [tolgraven.events]
            [tolgraven.subs]
            [tolgraven.react :as rf]
            [tolgraven.loader :as loader]
            [shadow.lazy :as lazy]
            [clojure.string :as string]
            [tolgraven.modules.neovim.module :as neovim]
            [tolgraven.modules.github.module]
            [tolgraven.modules.github.views :as github]
            [tolgraven.modules.github.diff-views :as diff-views]))

(defonce *unit (r/atom nil))
(defonce *workers (r/atom 0))
(defonce *created (r/atom 0))
(defmethod test/report [::test/default :end-run-tests] [summary] (reset! *unit summary))

(defn <fixture> [revision]
  [:main {:style {:max-width "90rem", :margin "1rem auto"}}
   [:p {:id "unit-results"} (pr-str @*unit)]
   [:p {:id "worker-count"} (str "Active workers: " @*workers "; created: " @*created)]
   (if revision
     [:<> [:a {:href "/fixtures/github-diff.html", :data-document-navigation "true"} "Back to commit list"]
      [diff-views/<viewer> revision]]
     [github/<commits>])])

(defn ^:export init! []
  ;; The focused fixture has one eager module, so Shadow's loadable is already ready.
  (set! loader/modules {:neovim (lazy/loadable tolgraven.modules.neovim.module/spec)})
  (let [original js/Worker]
    (set! js/window.Worker
      (fn [url]
        (let [worker (js/Reflect.construct original #js [url])
              terminate (.-terminate worker)
              *stopped? (atom false)]
          (swap! *created inc)
          (swap! *workers inc)
          (set! (.-terminate worker)
            (fn [] (when (compare-and-set! *stopped? false true)
                      (swap! *workers dec) (.call terminate worker))))
          worker))))
  (let [parts (string/split (.-pathname js/location) #"/")
        revision (when (= "github" (nth parts 2 nil)) (vec (drop 3 parts)))]
    (rf/dispatch-sync [:set [:options :github] {:user "tolgraven", :repo "tolgrAVen"}])
    (if revision
      (rf/dispatch [:github/diff-open revision])
      (rf/dispatch [:http/get {:uri "https://api.github.com/repos/tolgraven/tolgrAVen/commits?per_page=5"
                              :background? true}
                    [:github/save-commits 1]]))
    (run-tests 'tolgraven.github-diff-test)
    (dom/render (dom/create-root (.getElementById js/document "app")) [<fixture> revision])))
