(ns website.managed-image-test
  (:require [clojure.test :refer [deftest is]]
            [website.components.image.sources :as sources]
            [website.page :as page]))

(defn rendition [format width]
  {:src (str "https://images.example.test/objects/" format "?width=" width)
   :width width
   :height (/ (* width 9) 16)
   :bytes width
   :mime (str "image/" format)})

(def asset
  {:id "managed"
   :src "https://images.example.test/objects/original?id=managed"
   :width 1600
   :height 900
   :placeholder {:colors ["#123456" "#abcdef" "#fedcba"]}
   :renditions {:original {:src "https://images.example.test/objects/original?id=managed"
                          :width 1600
                          :height 900
                          :bytes 160000
                          :mime "image/jpeg"}
                :webp (mapv #(rendition "webp" %) [80 400 800 1600])
                :avif (mapv #(rendition "avif" %) [80 400 800 1600])}})

(deftest managed-sources-use-explicit-renditions-without-extension-guesses
  (is (= {:original (:src asset)
          :webp (:src (last (get-in asset [:renditions :webp])))
          :avif (:src (last (get-in asset [:renditions :avif])))}
         (sources/get-src-variants "https://cms.example.test/uploads/source.jpg" asset)))
  (is (= {:width 1600 :height 900} (sources/dimensions "source.jpg" asset)))
  (is (= {:width 80
          :webp "https://images.example.test/objects/webp?width=80"
          :avif "https://images.example.test/objects/avif?width=80"}
         (sources/preview "source.jpg" asset)))
  (is (sources/progressive? "source.jpg" asset))
  (is (= (str "https://images.example.test/objects/avif?width=80 80w, "
              "https://images.example.test/objects/avif?width=400 400w, "
              "https://images.example.test/objects/avif?width=800 800w, "
              "https://images.example.test/objects/avif?width=1600 1600w")
         (sources/srcset "source.jpg" "avif" asset)))
  (is (= "linear-gradient(to bottom, #123456 0%, #abcdef 50%, #fedcba 100%)"
         (sources/placeholder-background "source.jpg" asset)))
  (is (nil? (sources/placeholder-background "source.jpg"
              (assoc asset :placeholder {:colors ["url(untrusted)" "#123456" "#123456"]})))))

(deftest a-single-managed-candidate-has-no-progressive-preview
  (let [single (update asset :renditions
                      #(assoc % :webp [(last (:webp %))] :avif [(last (:avif %))]))]
    (is (nil? (sources/preview "source.jpg" single)))
    (is (not (sources/progressive? "source.jpg" single))))
  (is (= {:original "https://unmanaged.example.test/photo.png"}
         (sources/get-src-variants "https://unmanaged.example.test/photo.png"))))

(deftest critical-image-props-retain-ready-assets-and-rendered-sizes
  (let [url "https://cms.example.test/uploads/source.jpg"
        content {:intro {:bg [{:src url :asset asset :sizes "45vw"}]
                         :logo-bg url
                         :image-assets {url asset}}}
        spec {:preload-images [[:intro :bg 0 :src] [:intro :logo-bg]]}]
    (is (= [url] (page/critical-images spec content)))
    (is (= [{:src (:src asset) :asset asset :sizes "45vw"}
            {:src (:src asset) :asset asset}]
           (page/critical-image-attrs spec content)))))
