(ns website.icon-fonts-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [website.fonts.icons :as icons]))

(use-fixtures :each
  (fn [test]
    (let [state (var-get #'icons/*prepared)
          original @state]
      (try
        (reset! state {:assets {}})
        (test)
        (finally (reset! state original))))))

(deftest additions-extend-declarations-and-reject-invalid-references
  (let [declared (icons/selection {})
        extended (icons/selection {:solid #{61440}})]
    (is (every? (get extended :solid) (get declared :solid)))
    (is (contains? (:solid extended) 61440))
    (doseq [invalid [{:regular #{1}} {:solid [1]} {:brands #{-1}} {:solid #{65536}}]]
      (is (thrown? clojure.lang.ExceptionInfo (icons/selection invalid))))))

(deftest publication-is-atomic-reuses-unchanged-input-and-retains-issued-urls
  (let [calls (atom 0)
        fail? (atom false)
        handler (icons/wrap-assets (constantly {:status 418}))]
    (with-redefs [icons/compile-subsets!
                  (fn [selected]
                    (swap! calls inc)
                    (if @fail? (throw (ex-info "codec failed" {}))
                        (into {} (for [[kind points] selected]
                                   [kind (.getBytes (str kind (sort points)) "UTF-8")]))))]
      (let [first-snapshot (icons/prepare!)
            paths (map second (re-seq #"url\(\"([^\"]+)\"\)" (:css first-snapshot)))
            before (handler {:uri (first paths) :request-method :get})]
        (is (= first-snapshot (icons/prepare!)))
        (is (= 1 @calls))
        (is (= 2 (count paths)))
        (is (= 200 (:status before)))
        (is (string/includes? (:css first-snapshot) "U+20"))
        (is (not (re-find #"fa-(?:solid|brands)-|fallback" (:css first-snapshot))))
        (reset! fail? true)
        (is (thrown? clojure.lang.ExceptionInfo (icons/prepare! {:solid #{61440}})))
        (is (= first-snapshot (icons/prepare!)))
        (reset! fail? false)
        (is (not= first-snapshot (icons/prepare! {:solid #{61440}})))
        (is (= 200 (:status (handler {:uri (first paths) :request-method :get}))))
        (let [head (handler {:uri (first paths) :request-method :head})]
          (is (= (:headers before) (:headers head)))
          (is (nil? (:body head)))
          (is (= "public, max-age=31536000, immutable" (get-in head [:headers "Cache-Control"])))
          (is (= "font/woff2" (get-in head [:headers "Content-Type"])))
          (is (re-matches #"\"[a-f0-9]{64}\"" (get-in head [:headers "ETag"]))))))
    (is (= 404 (:status (handler {:uri "/webfonts/icons/missing.woff2" :request-method :get}))))
    (is (= 405 (:status (handler {:uri "/webfonts/icons/missing.woff2" :request-method :post}))))
    (is (= 418 (:status (handler {:uri "/unrelated" :request-method :get}))))))

(deftest real-codec-produces-small-complete-woff2-subsets
  (let [fonts (icons/compile-subsets! (icons/selection {}))]
    (is (= #{:solid :brands} (set (keys fonts))))
    (doseq [[kind data] fonts]
      (is (= "wOF2" (String. ^bytes data 0 4 "US-ASCII"))))
    (is (< (alength ^bytes (:solid fonts)) 10000))
    (is (< (alength ^bytes (:brands fonts)) 5000))))

(deftest existing-content-fields-supply-additional-glyphs-without-scanning-prose
  (let [selected (icons/content-selection
                   {:services {:categories [["Audio" "bullhorn" []] ["Web" "laptop" []]]}
                    :cv {:cv {:timeline [{:icon "fas fa-solid fa-briefcase"}]}}
                    :footer [{:links [{:icon "github"}]}]
                    :story {:text "fa-bug and solid/film are prose, not references"}})]
    (is (= #{61601 61705 61617} (:solid selected)))
    (is (= #{61595} (:brands selected)))
    (is (every? (:solid (icons/selection {})) (:solid selected)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (icons/content-selection {:services {:categories [["Bad" "../font" []]]}})))))
