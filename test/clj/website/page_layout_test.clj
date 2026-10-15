(ns website.page-layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [website.page-layout :as layout]
            [website.build.site :as site]))

(deftest independent-article-shapes
  (let [article {:heading {:title "Separate article"}, :title "Title", :text "Text", :images []}]
    (is (layout/valid-article? article))
    (doseq [invalid [(assoc article :heading "bad heading")
                     (assoc article :images [42])
                     (assoc article :images [["photo" {:src 42}]])
                     (assoc-in article [:heading :bg] {:src 42})]]
      (is (not (layout/valid-article? invalid))))))

(deftest portable-layout-contract
  (is (= layout/defaults (layout/validate! layout/defaults)))
  (let [page {:identity "custom", :path "/custom", :columns {:mobile 1, :tablet 2}
              :sections [{:identity "article", :component "story", :span 2}]}]
    (is (layout/valid-pages? [page]))
    (doseq [invalid [(assoc page :identity "../escape")
                     (assoc page :columns {:mobile 0})
                     (assoc page :sections [{:identity "module", :component "github", :index 1}])
                     (assoc page :columns {:desktop 2})
                     (assoc page :sections [{:identity "article", :component "story", :span 13}])
                     (assoc page :sections [{:identity "article", :component "story", :content {:text "missing title"}}])
                     (assoc page :sections (repeat 101 (first (:sections page))))]]
      (is (thrown? Exception (layout/validate! [invalid]))))
    (is (thrown? Exception (layout/validate! [page page])))
    (doseq [path ["/" "/blog" "/blog/custom" "/api/content" "/test/layout" "/cv"]]
      (is (thrown? Exception (layout/validate! [(assoc page :path path)]))))))

(deftest component-choices-follow-native-module-exports
  (let [declarations [{:id :new-feature, :layout-view? true}
                      {:id :workspace, :layout-view? false}]
        choices (layout/available-components {:modules {}} declarations)]
    (is (some #(= {:id "new-feature", :module :new-feature} %) choices))
    (is (not-any? #(= "workspace" (:id %)) choices))
    (is (not-any? #(= "new-feature" (:id %))
                  (layout/available-components {:modules {:new-feature {:enabled false}}} declarations)))
    (is (thrown? Exception (layout/validate-components!
                            [{:sections [{:component "not-exported"}]}] choices)))))

(deftest selection-preserves-order-and-independent-story
  (let [pages (layout/selected-pages {:modules {:strava {:enabled false}}})
        experiment (layout/find-page pages "experiments")]
    (is (= ["experiment-story" "experiment-index" "experiment-github"] (mapv :identity (:sections experiment))))
    (is (string? (get-in experiment [:sections 0 :content :text])))
    (is (nil? (:content (first (filter #(= "story" (:component %))
                                     (:sections (layout/find-page pages "home")))))))))

(deftest responsive-values-inherit-smaller-layouts
  (is (= 1 (layout/responsive-value {:mobile 1, :tablet 2, :large-monitor 4} "mobile" 1)))
  (is (= 2 (layout/responsive-value {:mobile 1, :tablet 2, :large-monitor 4} "small-monitor" 1)))
  (is (= 4 (layout/responsive-value {:mobile 1, :tablet 2, :large-monitor 4} "large-monitor" 1))))

(deftest build-settings-round-trip-layouts
  (let [settings (site/merge-config {:modules {}, :pages {}} {:modules {}, :layouts layout/defaults})]
    (is (= layout/defaults (:layouts settings)))
    (is (thrown? Exception (site/validate! (assoc settings :layouts "untrusted"))))))

(deftest layout-routes-keep-native-routing-data
  (is (= [["/experiments/layout" {:name :layout-experiments
                                  :layout "experiments"
                                  :preload-images []}]]
         (layout/routes layout/defaults))))
