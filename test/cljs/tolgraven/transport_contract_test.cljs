(ns tolgraven.transport-contract-test
  (:require [cljs.test :refer-macros [deftest is]]
            [cljs.reader :as edn]
            [tolgraven.ssr.return-contract :as contract]
            [tolgraven.ssr.schema :as ssr]
            [tolgraven.ssr.contract :as snapshot]
            [malli.core :as m]))

(deftest render-state-preserves-typed-content-and-view-state-without-runtime-or-credentials
  (let [db {:content {:blog {:heading "Saved heading" :session-title "A recording session"}}
            :component {"blog" {:settings {:folded #{[27 "root"]}}}}
            :store {:public {"blog-comments" {"root" {:text "Saved comment"}}}
                    :scoped {"query" {:docs []}} :private {:credentials "excluded"}}
            :state {:blog {:comment-thread-expanded {[27 "root"] true}}
                    :link-preview {:visited #{"https://example.org/read"}
                                   :active {:url "https://example.org/open" :status :expanded}
                                   :containers {"old-dom" {:count 1}}
                                   :prefetch-queue [{:url "https://example.org/open"}]}
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
    (is (= {:visited #{"https://example.org/read"}} (get-in saved [:state :link-preview])))
    (is (= #{[27 "root"]} (get-in saved [:component "blog" :settings :folded])))
    (is (nil? (get-in saved [:state :active-user :access-token])))
    (is (nil? (get-in saved [:state :login-field])))
    (is (nil? (get-in saved [:state :supabase-init])))
    (is (nil? (get-in saved [:state :booted :store])))
    (is (nil? (get-in saved [:state :booted :blog])))
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
    (is (.includes result "<title>A &lt; B &amp; C</title>"))
    (is (.includes result html))
    (is (.includes result "\\u003c/script>"))
    (is (not (.includes result "\"</script>\"")))))

(deftest storage-envelope-contract
  (is (m/validate ssr/storage-snapshot {:version 1 :schema 2 :expires-at 100 :value {:a []}}))
  (doseq [value [nil [] {:version 1 :schema 1 :expires-at "later" :value {}}
                       {:version 1 :schema 1 :expires-at 100}]]
    (is (not (m/validate ssr/storage-snapshot value)))))

(deftest hydration-and-node-share-module-owned-snapshot-state
  (let [post {:id 42 :title "Portable" :text "Body"}
        public (snapshot/snapshot-state {:kind :blog :path "/blog/post/42" :posts [post]
                                         :trusted-author-ids ["author"]})
        encoded (snapshot/snapshot-state {:kind :blog :path "/blog/post/42" :posts [post]
                                           :app-db-edn "{:state {:blog {:page 7 :comment-limit {42 20}}}}"})]
    (is (= 42 (get-in public [:state :blog :current-post-id])))
    (is (= ["author"] (get-in public [:options :supabase :trusted-author-ids])))
    (is (seq (get-in public [:store :scoped])))
    (is (= 7 (get-in encoded [:state :blog :page])))
    (is (= 20 (get-in encoded [:state :blog :comment-limit 42])))
    (is (nil? (get-in (snapshot/snapshot-state {:kind :cv :path "/cv" :posts []}) [:state :blog])))))

(deftest return-state-retains-owner-decisions-and-drops-runtime-layout
  (let [state {:menu true
               :scroll-position {"/blog/post/24" 850}
               :hidden {:header true :footer true}
               :scroll {:past-top true :at-bottom true :block true}
               :dispatch-in {:timer {:js-id 42}}
               :unknown-feature {:open? true}
               :blog {:comments-expanded {24 true}
                      :comment-thread-expanded {[24 "root"] true}
                      :restore-edits #{[:comments-expanded 24]}}
               :link-preview {:visited #{"https://example.org"} :active {:status :expanded}}}
        saved (contract/restored-view-state state)]
    (is (= {:menu true
            :scroll-position {"/blog/post/24" 850}
            :blog {:comments-expanded {24 true}
                   :comment-thread-expanded {[24 "root"] true}}
            :link-preview {:visited #{"https://example.org"}}} saved))
    (is (= saved (contract/restored-view-state saved)))))
