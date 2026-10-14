(ns website.schema-ownership-test
  (:require [cljs.test :refer-macros [deftest is]]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.modules.blog.schema :as blog]
            [website.modules.link-preview.schema :as link-preview]
            [website.validation.schema :as state]
            [website.modules.user.schema :as user]
            [website.modules.github.schema :as github]))

(deftest owned-contracts-validate-independently-and-through-assembly
  (doseq [[schema value invalid assembled]
          [[blog/post-fields {:title "Draft", :text "Body", :tags ["schema"]}
            {:tags [7]} {:form-field {:post-blog {:tags [7]}}}]
           [user/login-fields {:email "a@example.test", :password ""}
            {:email 3} {:form-field {:login {:email 3}}}]
           [github/state {:pages-fetched [1 2], :commits-fetched ["abc"]}
            {:pages-fetched ["1"]} {:github {:pages-fetched ["1"]}}]
           [link-preview/state {:containers nil, :active nil, :prefetch-queue []}
            {:prefetch {"/blog" :failed}} {:link-preview {:prefetch {"/blog" :failed}}}]]]
    (is (m/validate (dsl/->malli schema) value))
    (is (not (m/validate (dsl/->malli schema) invalid)))
    (is (not (m/validate (dsl/->malli state/state) assembled))))
  (is (m/validate (dsl/->malli state/state) {:form-field {:post-blog nil, :login {}}
                               :github {:pages-fetched []}
                               :link-preview {:active nil}})))

(deftest public-assembly-aliases-share-the-owned-contract
  (is (= link-preview/link-candidate state/link-candidate))
  (is (= link-preview/state state/link-preview)))
