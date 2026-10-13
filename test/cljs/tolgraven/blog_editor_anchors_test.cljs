(ns tolgraven.blog-editor-anchors-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tolgraven.modules.blog-editor.anchors :as anchors]))

(deftest anchors-follow-text-without-authored-markers
  (let [before "Hello end"
        after "New intro Hello end later"
        change (anchors/text-change before after)]
    (is (= after (str (subs before 0 (:from change)) (:inserted change) (subs before (:to change)))))
    (is (= 15 (anchors/map-position 5 [{:from 0, :to 0, :inserted "New intro "}])))
    (is (= "New intro Hello![file](url) end later"
           (anchors/insert-image after 15 "![file](url)")))))

(deftest native-multiple-edits-and-chip-deletion-are-explicit
  (is (= {"early" 3, "later" 9}
         (anchors/map-positions {"early" 1, "later" 8}
                                [{:from 0, :to 0, :inserted "++"}
                                 {:from 4, :to 6, :inserted "x"}])))
  (is (= 4 (anchors/map-position 4 [{:from 4, :to 4, :inserted "typed after chip"}])))
  (is (nil? (anchors/map-position 4 [{:from 2, :to 6, :inserted ""}]))))
