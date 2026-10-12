(ns tolgraven.supabase-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent.ratom :as ratom]
            [re-frame.core :as re-frame]
            [tolgraven.react :as rf]
            [tolgraven.events]
            [tolgraven.subs]
            [tolgraven.modules.blog.data :as blog-data]
            [tolgraven.supabase.query :as query]
            [tolgraven.supabase.runtime :as runtime]))

(defn pending-reader! [opts]
  (with-redefs [runtime/ensure-code! #(js/Promise. (fn [_ _]))
                runtime/ensure! #(js/Promise.resolve nil)]
    (runtime/ensure-query! opts)))

(deftest mounted-reader-recovers-after-a-later-chunk-acquisition
  (let [original @runtime/*api
        *acquired (atom 0)
        *disposed (atom 0)]
    (try
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (reset! runtime/*api
                {:ensure-query! (fn [_]
                                  (swap! *acquired inc)
                                  (ratom/make-reaction #(hash-map :docs [])
                                    :on-dispose #(swap! *disposed inc)))})
        (is (= 1 @*acquired) "The original pending binding acquires the retried module")
        (is (= {:docs []} @reader))
        (ratom/dispose! reader)
        (is (= 1 @*disposed) "Its pooled reader releases with its owner"))
      (finally (reset! runtime/*api original)))))

(deftest disposed-pending-reader-does-not-acquire-late-code
  (let [original @runtime/*api
        *acquired (atom 0)
        *disposed (atom 0)]
    (try
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (ratom/dispose! reader)
        (reset! runtime/*api {:ensure-query! (fn [_] (swap! *acquired inc))})
        (is (zero? @*acquired) "Unmounting removes the code installation observer"))
      (reset! runtime/*api nil)
      (let [reader (pending-reader! {:path-collection [:blog-posts]})]
        (reset! runtime/*api
                {:ensure-query! (fn [_]
                                  (ratom/make-reaction (constantly nil)
                                    :on-dispose #(swap! *disposed inc)))})
        (ratom/dispose! reader)
        (is (= 1 @*disposed) "Disposal before the next render releases a newly acquired reader"))
      (finally (reset! runtime/*api original)))))

(deftest first-cached-read-does-not-depend-on-a-warmed-core-subscription
  (let [restore! (re-frame/make-restore-fn)
        original @runtime/*api
        opts (blog-data/page-query 0 blog-data/page-size)
        key (pr-str (query/normalize-query opts))
        expected {:docs [{:id "42"
                          :data {:id 42
                                 :title "Cached SSR article"
                                 :text "Visible before SDK readiness"}}]}
        *reader (atom nil)]
    (try
      (reset! runtime/*api nil)
      (rf/dispatch-sync [:set [:store :scoped key] expected])
      (rf/clear-subscription-cache!)
      (reset! *reader (pending-reader! opts))
      ;; Alpha normalizes its query to a map. Reading the ordinary subscription
      ;; first would hide a broken alpha handler through their shared cache.
      (is (= expected @@*reader) "Restored content is visible on the first read before SDK code")
      (is (= expected (get-in @(rf/subscribe [:get :store]) [:scoped key])))
      (finally
        (when @*reader (ratom/dispose! @*reader))
        (reset! runtime/*api original)
        (restore!)))))
