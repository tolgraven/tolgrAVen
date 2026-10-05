(ns tolgraven.page-return-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [tolgraven.ssr.return-contract :as contract]))

(deftest render-state-preserves-typed-content-and-view-state-without-runtime-or-credentials
  (let [db {:content {:blog {:heading "Saved heading" :session-title "A recording session"}}
            :component {"blog" {:settings {:folded #{[27 "root"]}}}}
            :store {:public {"blog-comments" {"root" {:text "Saved comment"}}}
                    :scoped {"query" {:docs []}} :private {:credentials "excluded"}}
            :state {:blog {:comment-thread-expanded {[27 "root"] true}}
                    :active-user {:id "author" :access-token "secret"}
                    :login-field {:password "secret"}
                    :supabase-init :ready :booted {:store true :blog true}}
            :options {:supabase {:anon-key "excluded" :trusted-author-ids ["author"]}}
            :diagnostics {:expensive "excluded"}}
        saved (contract/state-for db)]
    (is (= saved (edn/read-string (pr-str saved))))
    (is (= "A recording session" (get-in saved [:content :blog :session-title])))
    (is (= "Saved comment" (get-in saved [:store :public "blog-comments" "root" :text])))
    (is (true? (get-in saved [:state :blog :comment-thread-expanded [27 "root"]])))
    (is (= #{[27 "root"]} (get-in saved [:component "blog" :settings :folded])))
    (is (nil? (get-in saved [:state :active-user :access-token])))
    (is (nil? (get-in saved [:state :login-field])))
    (is (nil? (get-in saved [:state :supabase-init])))
    (is (nil? (get-in saved [:state :booted :store])))
    (is (true? (get-in saved [:state :booted :blog])))
    (is (nil? (:diagnostics saved)))
    (is (nil? (get-in saved [:store :private])))))

(deftest return-pairs-are-exact-route-build-and-expiry-bound
  (let [snapshot {:version 1 :url "https://example.org/blog/post/27?x=1"
                  :build "a" :saved-at 100 :state-edn "{}"}
        expected {:url (:url snapshot) :build "a" :now 101}]
    (is (contract/valid? snapshot expected))
    (is (not (contract/valid? snapshot (assoc expected :url "https://example.org/blog/post/27?x=2"))))
    (is (not (contract/valid? snapshot (assoc expected :build "b"))))
    (is (not (contract/valid? snapshot (assoc expected :now 99))))
    (is (not (contract/valid? snapshot (assoc expected :now (+ 101 contract/ttl-ms)))))))

(deftest page-template-slots-do-not-reinterpret-user-content
  (let [template "<title>__LOCAL_PAGE_TITLE__</title>__LOCAL_PAGE_HTML__<script>__LOCAL_PAGE_STATE__</script>"
        html "<p>__LOCAL_PAGE_STATE__</p>"
        result (contract/document template html "{\"text\":\"</script>\"}" "A < B & C")]
    (is (.contains result "<title>A &lt; B &amp; C</title>"))
    (is (.contains result html))
    (is (.contains result "\\u003c/script>"))
    (is (not (.contains result "\"</script>\"")))))
