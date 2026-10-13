(ns tolgraven.markdown-upload-tree-test
  (:require [cljs.test :refer-macros [deftest is]]
            [tolgraven.modules.markdown-editor.upload-tree :as tree]))

(def parsed
  {:type "root"
   :position {:start {:offset 0}, :end {:offset 10}}
   :children [{:type "paragraph"
               :position {:start {:offset 0}, :end {:offset 10}}
               :children [{:type "strong"
                           :position {:start {:offset 0}, :end {:offset 10}}
                           :children [{:type "text", :value "abcdef"
                                       :position {:start {:offset 2}, :end {:offset 8}}}]}]}]})

(deftest pending-nodes-live-in-the-editor-tree-only
  (let [with-upload (tree/insert-uploads parsed {"job" 5}
                                        (fn [node position] (- position (get-in node [:position :start :offset]))))
        strong (get-in with-upload [:children 0 :children 0])
        prefix (tree/before-upload with-upload "job")]
    (is (= "abcdef" (get-in parsed [:children 0 :children 0 :children 0 :value])))
    (is (= ["text" "textDirective" "text"] (mapv :type (:children strong))))
    (is (= ["abc" "def"] (mapv :value (filter #(= "text" (:type %)) (:children strong)))))
    (is (= ["job"] (vec (tree/upload-ids with-upload))))
    (is (:found? prefix))
    (is (= "abc" (get-in prefix [:node :children 0 :children 0 :children 0 :value])))
    (is (empty? (tree/upload-ids (:node prefix))))))

(deftest empty-document-has-a-normal-inline-chip-paragraph
  (let [with-upload (tree/insert-uploads {:type "root", :children []} {"empty" 0} (fn [_ _] 0))]
    (is (= "paragraph" (get-in with-upload [:children 0 :type])))
    (is (= ["empty"] (vec (tree/upload-ids with-upload))))))

(deftest ready-images-import-through-one-stable-media-descriptor
  (let [image {:type "image", :url "/image.png", :alt "Image", :position {:start {:offset 5}, :end {:offset 25}}}
        imported (tree/import-images image [{:id "upload", :position 5, :image-inserted? true, :asset {:src "/image.png"}}])]
    (is (tree/upload-node? imported))
    (is (= {:id "upload", :src "/image.png", :alt "Image"} (:attributes imported)))
    (is (= (:position image) (:position imported)))
    (is (= "/image.png" (get-in (tree/import-images image []) [:attributes :src])))))

(deftest sized-images-export-canonical-layout-without-upload-bookkeeping
  (let [attributes {:id "transient-upload", :src "/image.png", :alt "Image", :layout-width "18rem", :layout-flow "left"}
        exported (tree/exported-image attributes)
        imported (tree/import-images exported [])]
    (is (= "mdxJsxTextElement" (:type exported)))
    (is (= "Component" (:name exported)))
    (is (not-any? #(= "id" (:name %)) (:attributes exported)))
    (is (= (dissoc attributes :id) (:attributes imported)))
    (is (= {:type "image", :url "/image.png", :alt "Image"}
           (tree/exported-image (dissoc attributes :layout-width :layout-flow))))
    (is (nil? (tree/exported-image {:id "pending"})))))
