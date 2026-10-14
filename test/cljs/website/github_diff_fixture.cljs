(ns website.github-diff-fixture
  "Live public GitHub reads and the real native diff component; no provider writes."
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [website.github-diff-test]
            [website.events]
            [website.subs]
            [website.react :as r]
            [website.loader :as loader]
            [shadow.lazy :as lazy]
            [clojure.string :as string]
            [website.modules.neovim.module :as neovim]
            [website.modules.github.module]
            [website.modules.github.views :as github]
            [website.modules.github.diff-views :as diff-views]))

(defonce *unit (r/atom nil))
(defonce *workers (r/atom 0))
(defonce *created (r/atom 0))
(defmethod test/report [::test/default :end-run-tests] [summary] (reset! *unit summary))

(r/reg-event-fx :github-fixture/commits
  (fn [_ [_ commits]]
    {:dispatch [:github/save-commits "tolgraven" "tolgrAVen" 1 1
                {:commits commits
                 :total (count commits)
                 :complete true
                 :next-cursor nil}]}))

(defn <fixture> [revision]
  [:main {:style {:max-width "90rem", :margin "1rem auto"}}
   [:p {:id "unit-results"} (pr-str @*unit)]
   [:p {:id "worker-count"} (str "Active workers: " @*workers "; created: " @*created)]
   (if revision
     [:<> [:a {:href "/fixtures/github-diff.html", :data-document-navigation "true"} "Back to commit list"]
      [diff-views/<viewer> revision]]
     [github/<repository> {}])])

(defn ^:export init! []
  ;; The focused fixture has one eager module, so Shadow's loadable is already ready.
  (set! loader/modules {:neovim (lazy/loadable website.modules.neovim.module/spec)})
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
    (r/dispatch-sync [:set [:module :github :configuration]
                      {:settings {:user "tolgraven", :repo "tolgrAVen"}}])
    (r/dispatch-sync [:set [:state :github]
                      {:target ["tolgraven" "tolgrAVen"]
                       :generation 1
                       :pages-pending #{1}}])
    (if revision
      (r/dispatch [:github/diff-open revision])
      (r/dispatch [:http/get {:uri "https://api.github.com/repos/tolgraven/tolgrAVen/commits?per_page=5"
                              :background? true}
                    [:github-fixture/commits]]))
    (run-tests 'website.github-diff-test)
    (r/render (r/create-root (.getElementById js/document "app")) [<fixture> revision])))
