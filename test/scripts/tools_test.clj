(ns tools-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [build.docker :as docker]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [media.images :as images]
            [website.components.image.eligibility :as eligibility]
            [test.server :as server]
            [util.process :as p]))

(deftest dependency-hash-excludes-application-source
  (let [directory (fs/create-temp-dir)]
    (try
      (let [manifests (mapv #(str (fs/path directory %)) ["Dockerfile.builder" "project.clj" "package.json" "package-lock.json"])]
        (doseq [file manifests] (spit file file))
        (with-redefs [docker/manifests manifests]
          (let [before (docker/builder-tag)]
            (spit (str (fs/path directory "source.cljs")) "changed app")
            (is (= before (docker/builder-tag)))
            (spit (last manifests) "changed dependencies")
            (is (not= before (docker/builder-tag))))))
      (finally (fs/delete-tree directory)))))

(deftest prefab-reuses-published-images-and-fails-on-auth-errors
  (let [calls (atom [])]
    (with-redefs [docker/builder-tag (constantly "registry/prefab:hash")
                  p/shell (fn [& _] {:exit 0})
                  docker/inspect (fn [_] [{:Id "same-image"}])
                  docker/registry-check! (fn [])
                  docker/remote-digest (constantly "same-image")
                  docker/run! #(swap! calls conj %&)]
      (is (= "registry/prefab:hash" (docker/prefab! true)))
      (is (not-any? #(some #{"build" "push"} %) @calls))
      (is (= ["docker" "buildx" "imagetools" "create"] (take 4 (first @calls)))))
    (reset! calls [])
    (with-redefs [docker/builder-tag (constantly "registry/prefab:hash")
                  p/shell (fn [& _] {:exit 1})
                  docker/remote-digest (constantly "existing-image")
                  docker/run! #(swap! calls conj %&)]
      (docker/prefab! false)
      (is (= [["docker" "pull" "registry/prefab:hash"]] (mapv vec @calls))))
    (with-redefs [p/shell (fn [& _] {:exit 1 :err "unauthorized"})]
      (is (thrown-with-msg? Exception #"Cannot inspect" (docker/remote-digest "registry/prefab:hash"))))))

(deftest deploy-scope-and-shell-quoting
  (with-redefs [docker/output (fn [& _] "https://github.com/example/new-site.git")]
    (is (thrown-with-msg? Exception #"scoped to tolgraven" (docker/require-checkout!))))
  (is (= "'a'\"'\"'b $(no)'" (docker/shell-quote "a'b $(no)"))))

(deftest cleanup-retains-rollback-builders-containers-and-unrelated-tags
  (let [repo (str docker/registry "/tolgraven/")
        image (fn [id age & tags] {:Id id :Created age :RepoTags tags})
        images [(image "latest" "9" (str repo "site:new"))
                (image "previous" "8" (str repo "site:previous"))
                (image "old" "7" (str repo "site:old") "another-project:saved")
                (image "used" "1" (str repo "site:used"))
                (image "prefab" "5" (str repo "builder:current"))
                (image "old-prefab" "4" "tolgraven-builder:local")
                (image "cms" "5" (str repo "strapi:v2"))
                (image "old-cms" "3" (str repo "strapi:v1"))]]
    (is (= #{(str repo "site:old") "tolgraven-builder:local" (str repo "strapi:v1")}
           (set (docker/cleanup-plan images #{"used"} "prefab" "latest"))))
    (is (not (some #{"tolgraven-builder:local"} (docker/cleanup-plan images #{} nil "old"))))
    (is (not (some #{(str repo "site:old")} (docker/cleanup-plan images #{} nil "old"))))))

(deftest image-verifier-fails-and-handles-spaces
  (is (images/original? "resources/public/a space\nfile.PNG"))
  (is (not (images/original? "resources/public/apple-touch-icon.png")))
  (with-redefs [images/originals (constantly ["missing image.PNG"])]
    (is (thrown-with-msg? Exception #"Missing image variants" (images/verify!)))))

(deftest responsive-discovery-selects-large-still-originals
  (is (images/responsive-original? "resources/public/img/large photo.JPG"))
  (is (images/responsive-original? "img/standalone.webp"))
  (is (images/responsive-original? "img/standalone.avif"))
  (doseq [path ["img/a-320w.png" "img/a-320w.avif" "img/a.svg" "img/a.gif"
                "img/uploads/a.png" "img/avatar.png" "img/apple-touch-icon.png" "img/favicon.avif" "img/android-chrome.webp"]]
    (is (not (images/responsive-original? path))))
  (is (images/responsive-eligible? {:width 960 :height 600 :bytes 65536 :frames 1}))
  (is (images/responsive-eligible? {:width 1200 :height 300 :bytes 100000 :frames 1}))
  (is (images/responsive-eligible? {:width 321 :height 960 :bytes 65536 :frames 1}))
  (is (images/responsive-eligible? {:width 950 :height 1194 :bytes 1454052 :frames 1}))
  (doseq [data [{:width 959 :height 959 :bytes 100000 :frames 1}
                {:width 320 :height 1200 :bytes 100000 :frames 1}
                {:width 960 :height 600 :bytes 65535 :frames 1}
                {:width 264 :height 262 :bytes 24776 :frames 1}
                {:width 1200 :height 600 :bytes 8000 :frames 1}
                {:width 1200 :height 600 :bytes 100000 :frames 2}]]
    (is (not (images/responsive-eligible? data)))))

(deftest automatic-small-icons-are-removed-without-changing-manual-overrides
  (let [icon {:width 264 :height 262 :sizes [80 160] :generated? true}
        manual {:width 250 :height 250 :sizes [40 80] :progressive? false}
        metadata {"img/cljs@2x.png" {:width 264 :height 262 :bytes 24776 :frames 1}
                  "img/manual.png" {:width 250 :height 250 :bytes 100000 :frames 1}}
        catalog (images/responsive-catalog {"img/cljs@2x.png" icon "img/manual.png" manual} metadata)]
    (is (not (contains? catalog "img/cljs@2x.png")))
    (is (= (assoc manual :bytes 100000) (get catalog "img/manual.png")))
    (is (empty? (images/responsive-catalog {} metadata)))))

(deftest responsive-discovery-selects-one-canonical-source-per-stem
  (with-redefs [fs/glob (fn [& _] ["resources/public/img/a.jpg" "resources/public/img/a.avif"
                                  "resources/public/img/a.webp" "resources/public/img/b.avif"
                                  "resources/public/img/b.webp" "resources/public/img/c.avif"
                                  "resources/public/img/a-320w.webp"])
                fs/regular-file? (constantly true)]
    (is (= ["resources/public/img/a.jpg" "resources/public/img/b.webp" "resources/public/img/c.avif"]
           (images/responsive-originals)))))

(deftest responsive-catalog-preserves-manual-overrides-and-exclusions
  (let [hero {:width 1189 :height 1189 :sizes [400 800] :progressive? true}
        excluded {:exclude? true}
        manual-off {:width 250 :height 250 :sizes [40 80] :progressive? false}
        before {"hero.jpg" hero
                "excluded.jpg" excluded
                "manual.png" manual-off
                "gone.png" {:generated? true :width 600 :height 600 :sizes [80 320]}}
        metadata {"hero.jpg" {:width 1189 :height 1189 :bytes 200000 :frames 1}
                  "excluded.jpg" {:width 600 :height 600 :bytes 200000 :frames 1}
                  "new.png" {:width 4096 :height 4096 :bytes 1000000 :frames 1}}
        catalog (images/responsive-catalog before metadata)]
    (is (= (assoc hero :bytes 200000) (get catalog "hero.jpg")))
    (is (= excluded (get catalog "excluded.jpg")))
    (is (= manual-off (get catalog "manual.png")))
    (is (not (contains? catalog "gone.png")))
    (is (= {:width 4096
            :height 4096
            :bytes 1000000
            :sizes [80 160 320 640 960 1280 1920 2560]
            :generated? true}
           (get catalog "new.png")))
    (is (= catalog (images/responsive-catalog catalog metadata)))
    (is (not (contains? (images/responsive-catalog catalog
                         (assoc-in metadata ["new.png" :frames] 2)) "new.png")))))

(deftest responsive-discovery-recognizes-apng-without-decoding
  (let [directory (fs/create-temp-dir)
        file (fs/path directory "animated.png")]
    (try
      (with-open [output (java.io.DataOutputStream. (java.io.FileOutputStream. (str file)))]
        (.writeLong output -8552249625308161526)
        (.writeInt output 8)
        (.writeBytes output "acTL"))
      (is (true? (images/animated-png? file)))
      (spit (str file) "actually JPEG data")
      (is (nil? (images/animated-png? file)))
      (with-open [output (java.io.DataOutputStream. (java.io.FileOutputStream. (str file)))]
        (.writeLong output -8552249625308161526)
        (.writeInt output 0)
        (.writeBytes output "IDAT"))
      (is (false? (images/animated-png? file)))
      (finally (fs/delete-tree directory)))))

(deftest responsive-widths-never-upscale-and-sidecars-stay-current
  (is (= [80] (images/responsive-sizes 160)))
  (is (= [80 160 320 640 960] (images/responsive-sizes 1189)))
  (let [directory (fs/create-temp-dir)
        original (fs/path directory "a photo.JPG")
        target (images/responsive-target original 320 "avif")]
    (try
      (spit (str original) "source")
      (is (not (images/current-variant? original target)))
      (spit (str target) "encoded")
      (is (images/current-variant? original target))
      (is (= :skipped (images/responsive-convert! original 320 "avif" false)))
      (finally (fs/delete-tree directory)))))

(deftest responsive-verification-rejects-incorrect-sized-output
  (with-redefs [clojure.core/slurp (constantly "{\"img/a.jpg\" {:width 600 :height 600 :sizes [80]}}")
                images/image-metadata! (constantly {:width 79 :height 80 :bytes 100 :frames 1})]
    (is (thrown-with-msg? Exception #"Invalid responsive variant dimensions"
                         (images/responsive-verify!))))
  (with-redefs [clojure.core/slurp (constantly "{\"img/a.jpg\" {:width 600 :height 600 :sizes [80]}}")
                images/image-metadata! (constantly {:width 80 :height 80 :bytes 100 :frames 1})]
    (is (re-find #"2 sized files, 200 bytes" (with-out-str (images/responsive-verify!))))))

(deftest static-server-paths-stay-inside-the-fixtures
  (is (nil? (server/route-file "/%2e%2e/private")))
  (is (= 400 (:status (server/handler {:uri "/%2e%2e/private"}))))
  (is (= 200 (:status (server/handler {:uri "/fixtures/live.html"}))))
  (is (= "test/fixtures/docs/index.html.gz" (str (server/route-file "/fixtures/docs/index.html"))))
  (is (= "test/fixtures/docs/docs.html.gz" (str (server/route-file "/fixtures/docs/docs.html"))))
  (is (= "test/browser/docs/private.html" (str (server/route-file "/fixtures/docs/private.html"))))
  (is (= "resources/public/js/tests/js/test.js" (str (server/route-file "/js/test.js")))))

(deftest static-docs-fixtures-retain-the-reviewed-generated-bytes
  (doseq [[page size digest]
          [["index" 379373 "0274b19eccfd0cf20da50f54d4c3e9056a214f44a0ecafc7866373effbebf757"]
           ["docs" 92834 "619f164b2dde82aaf464af42e475177c91e36926cc08cece2431f20660865ce2"]]]
    (let [response (server/handler {:uri (str "/fixtures/docs/" page ".html")})
          body (:body response)
          actual (format "%064x" (java.math.BigInteger.
                                   1 (.digest (java.security.MessageDigest/getInstance "SHA-256") body)))]
      (is (= 200 (:status response)))
      (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
      (is (= "no-store" (get-in response [:headers "cache-control"])))
      (is (nil? (get-in response [:headers "content-encoding"])) "The response is already decoded HTML")
      (is (= size (alength body)))
      (is (= digest actual)))))

(deftest pinned-docs-do-not-fall-back-to-generated-or-unrelated-files
  (with-redefs [fs/regular-file? (constantly false)]
    (is (= 404 (:status (server/handler {:uri "/fixtures/docs/index.html"}))))
    (is (= 404 (:status (server/handler {:uri "/fixtures/docs/docs.html"})))))
  (is (= 400 (:status (server/handler {:uri "/fixtures/docs/%2e%2e/private"}))))
  (is (= 404 (:status (server/handler {:uri "/fixtures/docs/index.html.gz"}))))
  (let [response (server/handler {:uri "/fixtures/live.html"})]
    (is (= 200 (:status response)))
    (is (= "text/html; charset=utf-8" (get-in response [:headers "content-type"])))
    (is (= (fs/file "test/browser/live.html") (:body response))
        "Ordinary assets remain native files, rather than entering Docs decoding")))

(deftest native-neovim-fixtures-scope-isolation-and-bundle-routing
  (doseq [uri ["/fixtures/neovim.html" "/fixtures/neovim-visual.html"
               "/vendor/neovim/nvim-profile-worker.js" "/vendor/neovim/nvim.wasm"]]
    (let [response (server/handler {:uri uri})]
      (is (= 200 (:status response)))
      (is (= "same-origin" (get-in response [:headers "Cross-Origin-Opener-Policy"])))
      (is (= "require-corp" (get-in response [:headers "Cross-Origin-Embedder-Policy"])))))
  (doseq [uri ["/fixtures/neovim-unsupported.html" "/fixtures/visual.html"]]
    (let [response (server/handler {:uri uri})]
      (is (= 200 (:status response)))
      (is (nil? (get-in response [:headers "Cross-Origin-Opener-Policy"])))
      (is (nil? (get-in response [:headers "Cross-Origin-Embedder-Policy"])))))
  (is (= "application/wasm" (get-in (server/handler {:uri "/vendor/neovim/nvim.wasm"}) [:headers "content-type"])))
  (is (= "resources/public/js/neovim-check/main.js" (str (server/route-file "/js/neovim-check/main.js"))))
  (is (= "resources/public/js/neovim-check/cljs-runtime/website.neovim_fixture.js"
         (str (server/route-file "/js/neovim-check/cljs-runtime/website.neovim_fixture.js")))))

(deftest child-timeout-is-enforced
  (let [start (System/nanoTime)]
    (is (thrown-with-msg? Exception #"timed out" (p/shell {:timeout 50 :out :string} ["sleep" "10"])))
    (is (< (/ (- (System/nanoTime) start) 1e6) 2000))))

(deftest scanner-and-runtime-share-the-substantial-original-cutoff
  (doseq [data [{:width 960 :height 600 :bytes 65536 :frames 1}
               {:width 321 :height 960 :bytes 65536 :frames 1}
               {:width 320 :height 1200 :bytes 100000 :frames 1}
               {:width 950 :height 950 :bytes 100000 :frames 1}
               {:width 1200 :height 600 :bytes 65535 :frames 1}
               {:width 165 :height 256 :bytes 7604 :frames 1}]]
    (is (= (images/responsive-eligible? data) (eligibility/eligible? data)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'tools-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
