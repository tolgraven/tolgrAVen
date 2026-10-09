(ns tolgraven.page-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.page :as page]
            [tolgraven.page-router :as router]))

(deftest title-selection-belongs-to-the-page-spec
  (let [snapshot {:content {:document {:title "Site title"}}
                  :product {:name "An unrelated page type"}}]
    (is (= "An unrelated page type"
           (page/document-title {:document-title #(get-in % [:product :name])} snapshot)))
    (is (= "Site title" (page/document-title {} snapshot)))
    (is (= "Site title" (page/document-title {:document-title (constantly nil)} snapshot)))
    (is (nil? (page/document-title {} {})))
    (is (= "Article title"
           (page/document-title (:data (router/match "/blog/post/17"))
                                (assoc snapshot :posts [{:title "Article title"}]))))))

(deftest critical-images-use-the-module-heading-spec
  (let [spec (:data (router/match "/blog/post/17"))
        content {:blog {:heading {:bg {:src "img/blog.jpg"}}}}]
    (is (= ["img/blog.jpg"] (page/critical-images spec content)))
    (is (= [] (page/critical-images spec {})))
    (is (= ["img/hero.png"]
           (page/critical-images {:preload-images [[:hero :src]]}
                                 {:hero {:src "img/hero.png"}})))))
