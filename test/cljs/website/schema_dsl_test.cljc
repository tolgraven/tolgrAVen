(ns website.schema-dsl-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])
            [website.schema.dsl :as dsl]
            [website.schema.common]
            [website.validation :as validation]
            [malli.core :as m]))

(deftest nullable-types-and-optional-keys
  (doseq [[short standard]
          [[:?string [:maybe :string]]
           [[:?vector :string] [:maybe [:vector :string]]]
           [[:?int {:min 1}] [:maybe [:int {:min 1}]]]
           [[:?or :int :string] [:maybe [:or :int :string]]]
           [[:?tuple :int :?string] [:maybe [:tuple :int [:maybe :string]]]]
           [[:?map-of :string :?int] [:maybe [:map-of :string [:maybe :int]]]]
           [[:?set :keyword] [:maybe [:set :keyword]]]
           [[:?sequential :string] [:maybe [:sequential :string]]]
           [[:?fn string?] [:maybe [:fn string?]]]
           [:domain/?name [:maybe :domain/name]]
           [[:?map [:?basic? :boolean] [:?language :?string]]
            [:maybe [:map [:basic? {:optional true} :boolean]
                     [:language {:optional true} [:maybe :string]]]]]
           [[:map [:account/?name :?string]]
            [:map [:account/name {:optional true} [:maybe :string]]]]
           [[:map [:?size {:title "Size", :optional false} [:?int {:min 1}]]]
            [:map [:size {:title "Size", :optional true} [:maybe [:int {:min 1}]]]]]
           [[:map ["name" {:optional true} :?string]]
            [:map ["name" {:optional true} [:maybe :string]]]]]]
    (is (= standard (dsl/->malli short)))
    (is (= standard (dsl/->malli standard)))
    (is (= standard (dsl/->malli (dsl/->malli short)))))
  (let [schema [:map {:closed true} [:?label :?string] [:?count :int] [:required :?string]]]
    (doseq [valid [{:required nil} {:required "x"} {:required nil :label nil :count 3}]]
      (is (nil? (validation/explain schema valid))))
    (doseq [invalid [{} {:required nil :count nil} {:required nil :label 3}
                     {:required nil :?label "x"}]]
      (is (seq (validation/explain schema invalid))))))

(deftest literals-properties-and-sequence-optionality-remain-native
  (let [schema [:map {:default {:literal :?string}, :title "?title"}
                [:?literal [:enum :?string [:?vector :?string]]]
                [:equality [:= {:key :?map}]]
                [:sequence [:cat [:? :?string] [:* [:?vector :int]]]]]
        expected [:map {:default {:literal :?string}, :title "?title"}
                  [:literal {:optional true} [:enum :?string [:?vector :?string]]]
                  [:equality [:= {:key :?map}]]
                  [:sequence [:cat [:? [:maybe :string]] [:* [:maybe [:vector :int]]]]]]]
    (is (= expected (dsl/->malli schema))))
  (doseq [schema [[:fn string?] [:re #"\?string"] [:ref :?id]
                  [:> 0] [:enum :?map :?string] [:map [:name]]]]
    (is (= schema (dsl/->malli schema))))
  (is (m/validate (dsl/->malli [:cat [:? :?string]]) []))
  (is (m/validate (dsl/->malli [:cat [:? :?string]]) [nil]))
  (is (not (m/validate (dsl/->malli [:cat [:? :?string]]) [1])))
  (let [compiled (m/schema [:map [:x :int]])]
    (is (identical? compiled (dsl/->malli compiled))))
  (let [schema (with-meta [:?vector :string] {:source :example})]
    (is (= {:source :example} (meta (second (dsl/->malli schema)))))))

(deftest nested-composition-and-local-registry
  (let [schema [:schema {:registry {::node [:map [:value :?string]
                                                 [:?children [:vector [:ref ::node]]]]}}
                [:ref ::node]]
        expanded (dsl/->malli schema)]
    (is (= [:map [:value [:maybe :string]]
            [:children {:optional true} [:vector [:ref ::node]]]]
           (get-in expanded [1 :registry ::node])))
    (is (nil? (validation/explain schema {:value nil :children [{:value "x"}]})))
    (is (seq (validation/explain schema {:value nil :children [{}]}))))
  (let [schema [:merge [:map [:?name :?string]] [:map [:?count [:?int {:min 1}]]]]]
    (is (nil? (validation/explain schema {:name nil :count 2})))
    (is (seq (validation/explain schema {:count 0}))))
  (is (= [:select-keys [:map [:name {:optional true} [:maybe :string]]] [:?name :name]]
         (dsl/->malli [:select-keys [:map [:?name :?string]] [:?name :name]])))
  (is (= [:multi {:dispatch :kind}
          [:?literal [:map [:name {:optional true} [:maybe :string]]]]]
         (dsl/->malli [:multi {:dispatch :kind} [:?literal [:map [:?name :?string]]]])))
  (doseq [type [:orn :catn :altn]]
    (is (= [type [:?tag [:maybe [:vector :string]]]]
           (dsl/->malli [type [:?tag [:?vector :string]]])))))

(deftest decoding-and-cached-compilation-use-expanded-schemas
  (let [schema [:map [:?count [:?int {:min 1}]] [:?active? :boolean]]
        compiled (validation/compiled schema)]
    (is (identical? compiled (validation/compiled schema)))
    (is (= {:count 3 :active? false}
           (validation/decode schema {:count "3" :active? "false"} :string)))
    (is (= {:count nil} (validation/decode schema {:count nil} :json)))
    (is (seq (validation/explain schema {:count 0})))
    (let [issues (validation/explain schema {:count "secret-value"})]
      (is (= [:count] (:path (first issues))))
      (is (not (re-find #"secret-value" (pr-str issues)))))))
