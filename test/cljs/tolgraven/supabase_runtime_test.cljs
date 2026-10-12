(ns tolgraven.supabase-runtime-test
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent.ratom :as ratom]
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
