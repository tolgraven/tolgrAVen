(ns tolgraven.build-authoring-test
  (:require [clojure.test :refer [deftest is]]
            [tolgraven.build.authoring :as authoring]))

(def source
  "(ns public.widgets)
   (def never-run (throw (Exception. \"must not execute\")))
   (m/defc <box> \"A useful documented box.\"
     {:spec-schema [:map [:text :string]]}
     [spec] [:p (:text spec)])
   (defc <plain> [text count] [:p text count])
   (defc ^:private <internal> [] nil)
   (when-enabled :blog :comments
     (defc <quote> \"Quote an original comment.\"
       {:spec-schema [:map [:id :string]]}
       [spec] [:p (:id spec)]))")

(deftest public-catalog-is-derived-without-running-source
  (let [entries (authoring/declarations source (constantly true))]
    (is (= ["public.widgets/<box>" "public.widgets/<plain>" "public.widgets/<quote>"] (mapv :name entries)))
    (is (= "A useful documented box." (:doc (first entries))))
    (is (= [:text :string] (second (:schema (first entries)))))
    (is (= :map (:mode (first entries))))
    (is (= :positional (:mode (second entries))))
    (is (= ["text" "count"] (:parameters (second entries))))))

(deftest unavailable-feature-removes-its-catalog-entry
  (is (= ["public.widgets/<box>" "public.widgets/<plain>"]
         (mapv :name (authoring/declarations source
                                            (fn [& requirements] (not= requirements '(:blog :comments))))))))

(deftest missing-docs-and-symbolic-schemas-remain-visible
  (let [entry (first (authoring/declarations
                      "(ns public.widgets) (defc <plain> {:spec-schema schema/record} [spec] nil)"
                      (constantly true)))]
    (is (= "public.widgets/<plain>" (:name entry)))
    (is (= 'schema/record (:schema entry)))
    (is (string? (:doc entry))))
  (is (thrown? Exception (authoring/declarations "#=(throw (Exception. \"never\"))" (constantly true)))))

(deftest selected-source-inventory-resolves-canonical-owners
  (let [entries (authoring/entries {})
        by-name (into {} (map (juxt :name identity)) entries)]
    (is (= :settings (:owner (get by-name "tolgraven.modules.settings.views/<config-table>"))))
    (is (get by-name "tolgraven.components.content.markdown/<image>"))
    (is (get by-name "tolgraven.modules.blog-editor.views/<post-blog>"))
    (is (not-any? #(= "Image" (:name %)) entries))))

(deftest annotated-signatures-use-the-existing-component-argument-parser
  (is (= ["attrs"] (:parameters (authoring/argument-shape '[attrs :- schema/image]))))
  (is (= ["spec"] (:parameters (authoring/argument-shape '[spec :- [:map [:text :string]]])))))
