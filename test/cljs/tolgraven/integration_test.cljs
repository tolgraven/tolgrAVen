(ns tolgraven.integration-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [reitit.core :as reitit]
            [tolgraven.routes :as routes]
            [tolgraven.loader :as loader]
            [tolgraven.events]
            [shadow.lazy :as lazy]
            [tolgraven.search.subs :as search]
            ;; Browser tests bundle all module specs so ready-module initialization is exercised.
            [tolgraven.blog.module]
            [tolgraven.cv.module]
            [tolgraven.docs.module]
            [tolgraven.search.module]
            [tolgraven.user.module]
            [tolgraven.chat.module]
            [tolgraven.github.module]
            [tolgraven.gpt.module]
            [tolgraven.strava.module]
            [tolgraven.instagram.module]
            [tolgraven.experiments]))

(deftest route-matching
  (doseq [[path route-name module]
          [["/" :home nil] ["/about" :about nil] ["/services" :services nil]
           ["/hire" :hire nil] ["/cv" :cv :cv] ["/docs" :docs :docs]
           ["/docs/codox/index" :docs-codox-page :docs]
           ["/blog" :blog :blog] ["/blog/page/2" :blog-page :blog]
           ["/blog/post/example-1" :blog-post :blog]
           ["/blog/tag/clojure" :blog-tag :blog]
           ["/blog/archive" :blog-archive :blog]
           ["/blog/new-post" :new-post :blog]
           ["/test/search" :test-tab :test]
           ["/client-oauth/twitter" :client-oauth-twitter nil]
           ["/not-found" :not-found nil]]]
    (let [data (:data (reitit/match-by-path routes/router path))]
      (is (= route-name (:name data)) path)
      (is (= module (:module data)) path)
      (when module
        (is (nil? (:view data)) "Module views stay out of eager route data"))))
  (is (= "/blog/new-post"
         (:path (reitit/match-by-name routes/router :new-post))))
  (is (nil? (reitit/match-by-path routes/router "/unknown"))))

(deftest late-module-response-does-not-replace-current-page
  (async done
    (let [*events (atom [])
          *navigation (atom 0)
          *resolve (atom nil)
          pending (js/Promise. (fn [resolve _] (reset! *resolve resolve)))
          dispatch! #(swap! *events conj %)
          old-match {:data {:name :cv :module :cv :page :page}}
          current-view (fn [] [:div "Current page"])
          current-match {:data {:name :home :view current-view}}]
      (routes/navigate! *navigation dispatch! (constantly pending) old-match)
      (routes/navigate! *navigation dispatch! (constantly pending) current-match)
      (@*resolve {:view {:page (fn [] [:div "Old page"])}})
      (js/setTimeout
        (fn []
          (let [navigations (filter #(= :common/navigate (first %)) @*events)]
            (is (= [current-match] (mapv second navigations)))
            (is (some #(= [:loading/off :page 1] %) @*events))
            (done)))
        0))))

(deftest module-load-failure-clears-loading
  (async done
    (let [*events (atom [])]
      (routes/navigate! (atom 0) #(swap! *events conj %)
                        (fn [_] (js/Promise.reject (js/Error. "Test load failure")))
                        {:data {:name :docs :module :docs :page :page}})
      (js/setTimeout
        (fn []
          (is (some #(= [:loading/off :page 1] %) @*events))
          (is (some #(= :diag/new (first %)) @*events))
          (is (some #(= [:error-page] (second %)) @*events))
          (done))
        0))))

(deftest cached-modules-initialize-once-and-return-native-promises
  (async done
    (let [id (keyword (str (random-uuid)))
          *initializations (atom 0)
          spec {:view {:page (fn [] [:div])}
                :init #(swap! *initializations inc)}
          loadable (reify
                     lazy/ILoadable (ready? [_] true)
                     IDeref (-deref [_] spec))]
      (with-redefs [loader/modules {id loadable}]
        (let [first-load (loader/load! {:module id})
              second-load (loader/load! {:module id})]
          (is (instance? js/Promise first-load))
          (is (fn? (.-finally second-load)))
          (-> (js/Promise.all #js [first-load second-load])
              (.then (fn [results]
                       (is (= 1 @*initializations))
                       (is (= spec (aget results 0) (aget results 1)))))
              (.catch (fn [error] (is false (str error))))
              (.finally done)))))))

(deftest literal-search-completions
  (doseq [query ["C++" "[x]" "a.b" "(fn"]]
    (let [result (search/autocomplete-suggestion
                   query {:highlights [{:snippet (str "Try <mark>" query "</mark> next")}]
                          :text_match 10})]
      (is (= (str query " next") (:text result)))))
  (is (nil? (search/autocomplete-suggestion "missing" {:highlights [{:snippet "Other text"}]})))
  (is (nil? (search/autocomplete-suggestion "query" {}))))
