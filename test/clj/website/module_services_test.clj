(ns website.module-services-test
  (:require [clojure.test :refer :all]
            [website.module-services :as services]))

(defn start-healthy! [] nil)
(defn start-missing! [] nil)

(deftest unavailable-service-preserves-healthy-services-and-starts-later-owners
  (let [*started (atom 0)
        *stopped (atom 0)]
    (with-redefs [start-healthy! (fn [] (swap! *started inc) #(swap! *stopped inc))
                  start-missing! (fn [] (throw (ex-info "Missing private module configuration" {})))]
      (let [cleanups (services/start! {:a {:start 'website.module-services-test/start-healthy!}
                                     :b {:start 'website.module-services-test/start-missing!}
                                     :c {:start 'website.module-services-test/start-healthy!}})]
        (is (= 2 @*started))
        (is (= 0 @*stopped))
        (is (= 2 (count cleanups)))
        (doseq [stop! (reverse cleanups)] (stop!))
        (is (= 2 @*stopped))))))
