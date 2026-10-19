(ns website.github-patch-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.modules.github.patch :as patch]
            [website.modules.github.events :as events]))

(def sample
  "@@ -10,3 +20,4 @@ function
 same
-old
+new
+extra
 last
\\ No newline at end of file
@@ -100 +200 @@
-before
+after
")

(deftest unified-lines-track-both-source-positions
  (let [hunks (patch/hunks sample)]
    (is (m/validate (dsl/->malli patch/hunks-schema) hunks))
    (is (= [[10 20] [11 nil] [nil 21] [nil 22] [12 23] [nil nil]]
           (mapv (juxt :old :new) (:rows (first hunks)))))
    (is (= [[100 nil] [nil 200]] (mapv (juxt :old :new) (:rows (second hunks)))))
    (is (= [:context :deletion :addition :addition :context :note]
           (mapv :kind (:rows (first hunks)))))
    (is (= ["before" "after"] (mapv :text (:rows (second hunks)))))))

(deftest zero-count-added-removed-and-source-text
  (is (= [{:kind :addition, :text "\t<unsafe>&λ", :old nil, :new 1}]
         (:rows (first (patch/hunks "@@ -0,0 +1 @@
+\t<unsafe>&λ
")))))
  (is (= [{:kind :deletion, :text "gone", :old 1, :new nil}]
         (:rows (first (patch/hunks "@@ -1 +0,0 @@
-gone")))))
  (is (= [{:kind :context, :text "", :old 7, :new 9}]
         (:rows (first (patch/hunks "@@ -7 +9 @@
 
")))))
  (is (= [] (patch/hunks nil)))
  (is (= [] (patch/hunks "Binary files differ"))))

(deftest detail-pages-aggregate-once-and-reject-outdated-pages
  (let [sha (apply str (repeat 40 "a"))
        db {:state {:github {:target ["u" "r"]
                             :generation 1
                             :query ""
                             :commits-pending #{sha}
                             :commit-pages-pending {sha 1}}}}
        files (mapv (fn [index] {:filename (str index ".clj")}) (range 100))
        first-page (events/save-commit-effects db "u" "r" 1 sha 1 {:sha sha, :files files})
        final-page (events/save-commit-effects (:db first-page) "u" "r" 1 sha 2
                                              {:sha sha, :files [{:filename "last.clj"}]})]
    (is (= 2 (get-in first-page [:dispatch 1 :url-params :page])))
    (is (nil? (events/save-commit-effects (:db first-page) "u" "r" 1 sha 1 {:sha sha, :files files})))
    (is (= 101 (count (get-in final-page [:db :content :github :commit sha :files]))))
    (is (= "last.clj" (:filename (last (get-in final-page [:db :content :github :commit sha :files])))))
    (is (empty? (get-in final-page [:db :state :github :commits-pending])))
    (is (= [sha] (get-in final-page [:db :state :github :commits-fetched])))
    (is (nil? (events/save-commit-effects (:db first-page) "u" "r" 0 sha 2 {:sha sha, :files []})))))
