(ns website.content-editor-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.test-support :as support]
            [website.editor-visual]
            [website.content.fields :as fields]
            [website.modules.content-editor.events]
            [website.modules.content-editor.module]
            [website.react :as r]))

(deftest nested-bindings-retain-native-data-and-field-paths
  (let [value {:title "Title", :images [{:src "/photo.png"}]}
        wrapped (fields/annotate value [:story] :edit)]
    (is (= value wrapped))
    (is (= [:story :images 0 :src] (fields/path (get-in wrapped [:images 0]) [:src])))
    (is (fields/editing? (get-in wrapped [:images 0])))))

(deftest editing-is-account-bound-and-drafts-preserve-the-loaded-value
  (async done
    (-> (go-promise
          (r/dispatch-sync [:init/app-db])
          (r/dispatch-sync [:editor-visual/install {:intro {:title "Original"}} true])
          (is (= :edit (await! (support/subscription-value! [:content/mode]))))
          (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "First"])
          (r/dispatch-sync [:content-editor/change [:intro :title] "New server value" "Second"])
          (let [draft (await! (support/state-at! [:state :content-editor :fields (pr-str [:intro :title])]))]
            (is (= "Original" (:expected draft)))
            (is (= "Second" (:value draft))))
          (r/dispatch-sync [:state [:active-user] nil])
          (is (= :view (await! (support/subscription-value! [:content/mode]))))
          (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "Unauthorized"])
          (is (= "Second" (:value (await! (support/state-at! [:state :content-editor :fields (pr-str [:intro :title])]))))))
        (.catch #(is false (str %)))
        (.finally done))))
