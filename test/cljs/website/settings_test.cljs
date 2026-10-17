(ns website.settings-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [website.modules.settings.events :as settings]))

(deftest appearance-values-retain-native-control-units
  (testing "Line widths remain pixels, including preferences saved in rem"
    (is (= "2px" (settings/css-value "2px" 17.6 "px")))
    (is (= "2px" (settings/css-value "0.125rem" 16 "px")))
    (is (= "2.2px" (settings/css-value "0.125rem" 17.6 "px")))
    (is (= "0px" (settings/css-value "0px" 11.52 "px"))))
  (testing "Spacing keeps its existing rem contract and rounding stays a percentage"
    (is (= "2rem" (settings/css-value "32px" 16)))
    (is (= "0.75rem" (settings/css-value "0.75rem" 11.52 "rem")))
    (is (= "5%" (settings/css-value "5%" 17.6)))))
