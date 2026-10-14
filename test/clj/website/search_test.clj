(ns website.search-test
  (:require [clojure.test :refer [deftest is]]
            [website.modules.search.model :as model]
            [website.modules.search.index :as index]
            [website.modules.search.typesense :as typesense]
            [website.modules.search.api :as api]
            [website.modules.github.search :as github-search]
            [website.modules.github.sync :as github-sync]
            [website.platform.supabase :as platform]))

(def document
  {:id "blog-post-42"
   :kind "blog-post"
   :title "C++ [x]"
   :text "A public post"
   :href "/blog/post/42"})

(defn unavailable-source-filter! [] (throw (ex-info "Public scope unavailable" {})))

(deftest unavailable-source-is-omitted-without-disabling-other-public-hits
  (let [sources [{:kind "blog-post", :preview :blog-preview/post}
                 {:kind "github-commit", :preview :github-preview/commit
                  :filter 'website.search-test/unavailable-source-filter!}]
        *query (atom nil)]
    (with-redefs [index/sources (constantly sources)
                  typesense/search! (fn [parameters]
                                      (reset! *query parameters)
                                      {:found 1, :hits [{:document document}]})]
      (let [response (api/response! {:q "public"})]
        (is (= 200 (:status response)))
        (is (= "kind:=blog-post" (:filter_by @*query)))
        (is (= ["blog-post-42"] (mapv #(get-in % [:document :id]) (get-in response [:body :hits]))))))))

(deftest marks-are-text-and-completions-do-not-interpret-user-regex
  (is (= [{:text "safe ", :marked? false}
          {:text "<script>bad</script>", :marked? true}]
         (model/marked-segments "safe <mark><script>bad</script></mark>")))
  (is (= [{:text "<mark>unclosed", :marked? false}] (model/marked-segments "<mark>unclosed")))
  (is (= "C++ [x]" (model/completion "C++" "C++ [x]")))
  (is (= "New features" (model/completion "features" "New features")))
  (is (= "(< x 3) (> y 2)" (model/plain-text "(< x 3) (> y 2)"))))

(deftest destinations-have-independent-delivery-identities
  (with-redefs [typesense/settings (constantly {:url "http://one:8108", :collection "website_v1"})]
    (let [first (index/consumer)]
      (is (= first (index/consumer)))
      (with-redefs [typesense/settings (constantly {:url "http://two:8108", :collection "website_v1"})]
        (is (not= first (index/consumer)))))))

(deftest import-success-is-per-row-and-matching-id
  (with-redefs [typesense/collection-path (constantly "/collections/fixture")
                typesense/request! (fn [& _] {:body "{\"success\":true,\"id\":\"blog-post-42\"}\n{\"success\":false,\"id\":\"bad\"}"})]
    (is (= [true false false] (typesense/import! [document (assoc document :id "bad") (assoc document :id "missing")]))))
  (with-redefs [typesense/request! (fn [& _] {:body "{\"success\":true,\"id\":\"wrong\"}"})]
    (is (= [false] (typesense/import! [document])))))

(deftest network-failures-release-each-owned-delivery-for-retry
  (let [calls (atom [])]
    (with-redefs [index/consumer (constantly "fixture")
                  index/rpc! (fn [operation parameters]
                               (if (= operation "website_search_claim")
                                 [{:id (:id document), :revision 3, :document document}
                                  {:id "deleted", :revision 4, :document nil}]
                                 (swap! calls conj parameters)))
                  typesense/import! (fn [_] (throw (ex-info "Offline" {})))
                  typesense/delete-many! (fn [_] (throw (ex-info "Offline" {})))]
      (is (= 2 (index/work!)))
      (is (= [false false] (mapv :p_success @calls)))
      (is (= [3 4] (mapv :p_revision @calls)))
      (is (= 1 (count (set (map :p_worker @calls))))))))

(deftest public-previews-omit-extension-fields-and-full-document-text
  (let [hit (api/public-hit [{:kind "blog-post", :preview :blog-preview/post}]
                           {:document (assoc document :text (apply str (repeat 2000 "x")) :private "hidden")})]
    (is (nil? (get-in hit [:document :private])))
    (is (= 260 (count (get-in hit [:document :text]))))
    (is (= 260 (count (get-in hit [:excerpt 0 :text]))))))

(deftest github-search-fails-closed-against-current-repository-visibility
  (with-redefs [github-sync/target! (constantly {:owner "owner", :repo "repo"})]
    (doseq [rows [[] [{:is_public false}]]]
      (with-redefs [platform/request! (fn [& _] {:body rows})]
        (is (nil? (github-search/filter!)))))
    (with-redefs [platform/request! (fn [& _] {:body [{:owner "owner", :repo "repo", :is_public true}]})]
      (is (= "repository:=`owner/repo`" (github-search/filter!))))))
