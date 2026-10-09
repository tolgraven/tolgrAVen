(ns tolgraven.ui-scoping-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader :as reader]
            [reagent.core :as r]
            [tolgraven.components.ui :as ui]
            [tolgraven.components.error :as error]
            [tolgraven.util :as util]
            [tolgraven.test-support :as support]))

(deftest numeric-display-preserves-default-explicit-and-invalid-precision-behavior
  (is (= 0 (util/format-number nil)))
  (is (= 0 (util/format-number 0)))
  (is (= 12.346 (util/format-number 12.3456)))
  (is (= 12 (util/format-number 12.3456 0)))
  (is (= -12.35 (util/format-number -12.3456 2)))
  (is (= 12.3456 (util/format-number 12.3456 -1)))
  (is (= "unavailable" (util/format-number "unavailable"))))

(deftest optional-inspector-retains-formatted-values-error-details-and-retry
  (async done
    (-> (go-promise
          (let [element (.createElement js/document "div")
                root (await! (support/create-root! element))
                value {:nested {:values [1 2 3] :label "Preserved"}}
                *error (r/atom {:error (ex-info "Readable error" {})})]
            (try
              (await! (support/render! root [ui/<formatted-data> "Data" value]))
              (await! (support/wait-for!
                        #(some-> (.querySelector element "pre") .-textContent
                                 (.includes "Preserved"))))
              (is (= value (reader/read-string (.-textContent (.querySelector element "pre")))))
              (is (= "Data" (.-textContent (.querySelector element "h5"))))
              (await! (support/render! root [error/<error-full> "test" "view" *error value]))
              (await! (support/wait-for!
                        #(some-> (.querySelector element "button") .-textContent
                                 (= "Attempt reload"))))
              (is (.includes (.-textContent element) "Readable error"))
              (is (.includes (.-textContent element) "Props/spec:"))
              (.click (.querySelector element "button"))
              (is (nil? @*error))
              (finally (support/unmount! root)))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
