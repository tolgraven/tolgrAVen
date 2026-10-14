(ns website.content-editor-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.test-support :as support]
            [website.editor-visual]
            [website.content.fields :as fields]
            [website.modules.content-editor.events :as editor-events]
            [website.modules.cv.views :as cv]
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

(deftest scalar-image-fields-retain-their-public-write-path
  (let [owner (fields/annotate {:logo "/logo.png"} [:cv :cv :timeline 0 :things 0] :edit)
        attrs {:src "/logo.png", :alt "Company logo"}
        bound (fields/image-attrs owner [:logo] attrs)]
    (is (= attrs bound))
    (is (= [:cv :cv :timeline 0 :things 0 :logo] (fields/path bound [:src])))
    (is (fields/editing? bound))))

(deftest nested-cv-bindings-retain-stable-owner-identities
  (let [owner {:id 12, :from 2020, :what "Group", :children [{:id "child", :from 2021, :what "Role", :logo "/logo.png"}]}
        wrapped (fields/annotate owner [:cv :cv :timeline 0 :things 0] :edit)
        child (get-in wrapped [:children 0])
        identities (:content/identities (meta child))]
    (is (= [{:path [:cv :cv :timeline 0 :things 0], :id 12}
            {:path [:cv :cv :timeline 0 :things 0 :children 0], :id "child"}] identities))
    (is (= identities (:content/identities (meta (fields/image-attrs child [:logo] {:src "/logo.png"})))))
    (is (= {:identities identities, :value "Draft"}
           (fields/current-draft {:identities identities, :value "Draft"} child)))
    (is (nil? (fields/current-draft {:identities (assoc-in identities [1 :id] "moved-child"), :value "Draft"} child)))))

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
          (r/dispatch-sync [:content-editor/change [:intro :text] "Published\nSecond line" "Private draft\nSecond line"])
          (r/dispatch-sync [:state [:active-user] nil])
          (is (= :view (await! (support/subscription-value! [:content/mode]))))
          (is (= "<p>Published</p>"
                 (r/render-to-string [fields/<text> :p
                                      (fields/annotate {:text "Published\nSecond line"} [:intro] :view)
                                      [:text] nil 0])))
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

(deftest cv-entry-parsing-and-origin-geometry
  (let [fields {:kind "container", :position "Description", :from "2020", :to "2024",
                :what " Group ", :where "", :how "First\nSecond"}]
    (is (= {:kind "container", :position "Description", :from 2020, :to 2024,
            :what "Group", :where "", :how ["First" "Second"]}
           (editor-events/cv-entry-value fields 2026)))
    (is (nil? (editor-events/cv-entry-value (assoc fields :to "2019") 2026)))
    (is (nil? (editor-events/cv-entry-value (assoc fields :from "3000" :to "") 2026)))
    (is (= "translate(-50%, -50%) translate(-25vw, -20vh) scale(0.5, 0.5)"
           (cv/origin-transform {:left 100, :top 100, :width 200, :height 100} 400 200 800 500)))))

(deftest cv-creation-retains-failed-drafts-and-guards-account-and-other-edits
  (async done
    (let [restore! (r/make-restore-fn)
          requests (atom [])]
      (r/reg-fx :content-editor/request #(swap! requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:editor-visual/install {:intro {:title "Original"}} true])
            (r/dispatch-sync [:content-editor/cv-open 0 "work" 3 [] []])
            (doseq [[field value] {:kind "container", :from "2020", :to "2024", :what "Grouping"}]
              (r/dispatch-sync [:content-editor/cv-change field value]))
            (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "Draft"])
            (r/dispatch-sync [:content-editor/cv-create 2026])
            (is (empty? @requests))
            (is (seq (:error (await! (support/state-at! [:state :content-editor :cv-entry])))))
            (r/dispatch-sync [:content-editor/change [:intro :title] "Original" "Original"])
            (r/dispatch-sync [:content-editor/cv-create 2026])
            (is (= 1 (count @requests)))
            (is (= :post (:method (first @requests))))
            (is (= "/api/content/cv-entry" (:uri (first @requests))))
            (is (= "container" (get-in (first @requests) [:data :entry :kind])))
            (r/dispatch-sync [:content-editor/cv-create 2026])
            (r/dispatch-sync [:content-editor/cv-cancel])
            (is (= :saving (:status (await! (support/state-at! [:state :content-editor :cv-entry])))))
            (is (= 1 (count @requests)))
            (r/dispatch-sync [:content-editor/cv-failed "visual-admin" {:message "Unavailable"}])
            (let [draft (await! (support/state-at! [:state :content-editor :cv-entry]))]
              (is (= "Grouping" (get-in draft [:fields :what])))
              (is (= :error (:status draft)))
              (is (m/validate (dsl/->malli schema/cv-entry-draft) draft)))
            (r/dispatch-sync [:state [:active-user] nil])
            (r/dispatch-sync [:content-editor/cv-failed "visual-admin" {:message "Old response"}])
            (is (= "Unavailable" (:error (await! (support/state-at! [:state :content-editor :cv-entry]))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))
