(ns website.media-svg-test
  (:require [clojure.test :refer [deftest is]]
            [website.media.svg :as svg]
            [website.media.image :as image]
            [website.media.pipeline :as pipeline]
            [website.media.schema :as schema]
            [website.components.image.sources :as sources]
            [website.validation :as validation]
            [website.media.storage :as storage]))

(defn svg-bytes [text] (.getBytes text "UTF-8"))
(defn vector-image [body]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 320 160\">" body "</svg>"))

(deftest native-vectors-retain-geometry-and-local-style-references
  (let [source (vector-image "<defs><linearGradient id=\"paint\"><stop offset=\"0\" stop-color=\"red\"/></linearGradient></defs><style>.a {fill:url('#paint')}</style><path class=\"a\" d=\"M0 0L320 160\"/>")]
    (is (= {:width 320 :height 160} (svg/inspect! (svg-bytes source))))
    (is (= "svg" (storage/extension! (svg-bytes source)))))
  (is (= {:width 640 :height 320}
         (svg/inspect! (svg-bytes "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"640px\" viewBox=\"0 0 320 160\"/>"))))
  (is (= {:width 96 :height 48}
         (svg/inspect! (svg-bytes "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1in\" height=\"0.5in\"/>")))))

(deftest vectors-do-not-execute-active-content-or-resolve-external-resources
  (doseq [body ["<script>alert(1)</script>"
                "<path onload=\"alert(1)\"/>"
                "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\"/></foreignObject>"
                "<use href=\"https://example.test/private.svg#shape\"/>"
                "<style>@import 'https://example.test/font.css';</style>"
                "<path style=\"fill:url(https://example.test/image.svg)\"/>"
                "<path fill=\"url(https://example.test/image.svg)\"/>"
                "<animate attributeName=\"href\" to=\"javascript:alert(1)\"/>"
                "<style>path {fill:u\\72l(https://example.test/image.svg)}</style>"]]
    (is (thrown? clojure.lang.ExceptionInfo (svg/inspect! (svg-bytes (vector-image body))))))
  (doseq [source [(str "<!DOCTYPE svg [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>" (vector-image "<text>&external;</text>"))
                  (str "<?xml-stylesheet href='https://example.test/style.css'?>" (vector-image ""))
                  "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                  "<html xmlns=\"http://www.w3.org/1999/xhtml\"/>"]]
    (is (thrown? clojure.lang.ExceptionInfo (svg/inspect! (svg-bytes source))))))

(deftest vector-publication-preserves-svg-and-never-runs-raster-codecs
  (let [source (svg-bytes (vector-image "<path d=\"M0 0L320 160\"/>"))
        *published (atom [])
        job {:id "vector", :content_hash (storage/digest source), :policy_version pipeline/policy-version}
        asset (with-redefs-fn {#'image/run-converter! (fn [& _] (throw (ex-info "SVG invoked a raster codec" {})))
                              #'storage/public-url #(str "https://storage.test/" %)
                              #'storage/put! (fn [path mime data]
                                               (swap! *published conj {:path path, :mime mime, :bytes data}))}
                #(image/with-renditions! source "svg" (fn [directory manifest]
                                                       (pipeline/publish! job directory manifest))))]
    (is (nil? (validation/explain schema/asset asset)))
    (is (= [:original] (vec (keys (:renditions asset)))))
    (is (= "image/svg+xml" (get-in asset [:renditions :original :mime])))
    (is (= {:width 320 :height 160} (sources/dimensions (:src asset) asset)))
    (is (= {:original (:src asset)} (sources/get-src-variants (:src asset) asset)))
    (is (nil? (sources/preview (:src asset) asset)))
    (is (not (sources/progressive? (:src asset) asset)))
    (is (not (sources/should-use-modern-formats? (:src asset) asset)))
    (is (= 1 (count @*published)))
    (is (= "image/svg+xml" (:mime (first @*published))))
    (is (= (seq source) (seq (:bytes (first @*published)))))
    (is (re-find #"original-[a-f0-9]{64}\.svg$" (:path (first @*published))))))
