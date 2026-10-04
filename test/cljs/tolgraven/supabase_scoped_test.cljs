(ns tolgraven.supabase-scoped-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [reagent.ratom :as ratom]
            [re-frame.db :as db]
            [tolgraven.supabase.scoped :as scoped]
            [tolgraven.supabase-client-test :as mocks]))

(deftest readers-coalesce-refreshes-and-release-streams-without-evicting-content
  (async done
    (let [mock (mocks/mock-client) before @db/app-db
          *reads (atom []) *callbacks (atom [])
          opts {:scoped? true :path-collection [:blog-posts] :where [[:id :== 42]]}
          old @scoped/*transport]
      (scoped/connect! (:sdk mock)
                       (fn [query success error]
                         (swap! *reads conj query) (swap! *callbacks conj [success error])))
      (let [reader (scoped/ensure-query! opts)]
        (is (identical? reader (scoped/ensure-query! opts)))
        (scoped/drain!)
        (is (= [opts] @*reads))
        ((ffirst @*callbacks) {:docs [{:id "42" :data {:id 42 :title "Ready"}}]})
        (-> (mocks/tick!)
            (.then (fn [_]
                     (is (= "Ready" (get-in @reader [:docs 0 :data :title])))
                     (let [change @(get-in @(:channels mock) ["blog-scoped-blog_posts" :change])]
                       (change #js {}) (change #js {}))
                     (scoped/drain!)
                     (is (= 2 (count @*reads)) "Invalidations in one tick share one filtered read")
                     (ratom/dispose! reader)
                     (scoped/drain!)
                     (is (= 1 (count @(:removed mock))))
                     ;; A late response from an unmounted reader cannot overwrite
                     ;; retained state or recreate a stream.
                     ((first (second @*callbacks)) {:docs []})
                     (mocks/tick!)))
            (.then (fn [_]
                     (is (= "Ready" (get-in @db/app-db [:store :scoped (scoped/query-key opts)
                                                       :docs 0 :data :title])))))
            (.catch #(is false (str %)))
            (.finally (fn [] (ratom/dispose! reader)
                        (scoped/connect! (:client old) (:read! old))
                        (reset! db/app-db before) (done))))))))
