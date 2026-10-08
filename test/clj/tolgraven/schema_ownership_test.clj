(ns tolgraven.schema-ownership-test
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [tolgraven.modules.blog.schema :as blog]
            [tolgraven.modules.link-preview.schema :as link-preview]
            [tolgraven.schema.state :as state]
            [tolgraven.modules.user.schema :as user]
            [tolgraven.modules.github.schema :as github]))

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
    (is (m/validate schema value))
    (is (not (m/validate schema invalid)))
    (is (not (m/validate state/state assembled))))
  (is (m/validate state/state {:form-field {:post-blog nil, :login {}}
                               :github {:pages-fetched []}
                               :link-preview {:active nil}})))

(deftest public-assembly-aliases-share-the-owned-contract
  (is (= link-preview/link-candidate state/link-candidate))
  (is (= link-preview/state state/link-preview)))
