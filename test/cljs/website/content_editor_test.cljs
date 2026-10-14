(ns website.content-editor-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.test-support :as support]
            [website.editor-visual]
            [website.content.fields :as fields]
            [website.modules.content-editor.events]
            [website.modules.content-editor.module]
            [website.modules.content-editor.schema :as schema]
            [website.schema.dsl :as dsl]
            [malli.core :as m]
            [website.react :as r]))

(deftest nested-bindings-retain-native-data-and-field-paths
  (let [value {:title "Title", :images [{:src "/photo.png"}]}
        wrapped (fields/annotate value [:story] :edit)]
    (is (= value wrapped))
    (is (= [:story :images 0 :src] (fields/path (get-in wrapped [:images 0]) [:src])))
    (is (fields/editing? (get-in wrapped [:images 0])))))

(deftest route-overrides-do-not-write-static-cms-fields
  (let [content (fields/annotate {:title "CMS", :bg {:src "/photo.png"}} [:blog :heading] :edit)
        overridden (fields/overridden content {:title "Route title"})]
    (is (= "Route title" (:title overridden)))
    (is (nil? (fields/path overridden [:title])))
    (is (= [:blog :heading :bg :src] (fields/path (fields/media overridden [:bg]) [:src])))))

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

(deftest save-queue-waits-for-each-authoritative-response
  (async done
    (let [restore! (r/make-restore-fn)
          requests (atom [])]
      (r/reg-fx :content-editor/request #(swap! requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:editor-visual/install {:intro {:title "Original", :text "Text"}} true])
            (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "Title saved"])
            (r/dispatch-sync [:content-editor/change [:intro :text] "Text" "Text saved"])
            (r/dispatch-sync [:content-editor/save-all])
            (await! (support/wait-for! #(when (= 1 (count @requests)) true)))
            (is (= 1 (count @requests)))
            (let [request (first @requests)
                  field (last (get-in request [:data :path]))
                  first-value (get-in request [:data :value])
                  bundle {:version 1, :content {:intro (assoc {:title "Original", :text "Text"} (keyword field) first-value)}}]
              (r/dispatch-sync (conj (:success request) bundle)))
            (await! (support/wait-for! #(when (= 2 (count @requests)) true)))
            (is (= 2 (count @requests)))
            (r/dispatch-sync (conj (:success (second @requests))
                                  {:version 1, :content {:intro {:title "Title saved", :text "Text saved"}}}))
            (await! (support/settle!))
            (is (empty? (await! (support/state-at! [:state :content-editor :fields]))))
            (is (= "Text saved" (:text (await! (support/subscription-value! [:content [:intro]]))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest conflict-retains-the-draft-and-installs-the-current-value
  (async done
    (-> (go-promise
          (r/dispatch-sync [:editor-visual/install {:intro {:title "Original"}} true])
          (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "Draft"])
          (let [key (pr-str [:intro :title])]
            (r/dispatch-sync [:content-editor/failed "visual-admin" key
                              {:response {:error "Changed in Strapi"
                                          :content-bundle {:version 1, :content {:intro {:title "Latest"}}}}}])
            (await! (support/settle!))
            (is (= "Draft" (:value (await! (support/state-at! [:state :content-editor :fields key])))))
            (is (= "Latest" (:title (await! (support/subscription-value! [:content [:intro]])))))
            (r/dispatch-sync [:content-editor/discard-field key])
            (is (nil? (await! (support/state-at! [:state :content-editor :fields key]))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest empty-image-upload-is-a-valid-draft
  (async done
    (let [restore! (r/make-restore-fn)
          uploads (atom [])]
      (r/reg-fx :content-editor/upload #(swap! uploads conj %))
      (-> (go-promise
            (r/dispatch-sync [:editor-visual/install {:services {:caption "Services"}} true])
            (r/dispatch-sync [:content-editor/upload [:services :bg :src] nil #js {}])
            (let [draft (await! (support/state-at! [:state :content-editor :fields (pr-str [:services :bg :src])]))]
              (is (= :uploading (:status draft)))
              (is (nil? (:expected draft)))
              (is (= "" (:value draft)))
              (is (m/validate (dsl/->malli schema/draft) draft)))
            (is (= 1 (count @uploads))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))
