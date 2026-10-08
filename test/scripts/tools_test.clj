(ns tools-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [build.docker :as docker]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [media.images :as images]
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
      (is (empty? @calls)))
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

(deftest static-server-paths-stay-inside-the-fixtures
  (is (nil? (server/route-file "/%2e%2e/private")))
  (is (= 400 (:status (server/handler {:uri "/%2e%2e/private"}))))
  (is (= 200 (:status (server/handler {:uri "/fixtures/live.html"}))))
  (is (= "resources/public/js/tests/js/test.js" (str (server/route-file "/js/test.js")))))

(deftest child-timeout-is-enforced
  (let [start (System/nanoTime)]
    (is (thrown-with-msg? Exception #"timed out" (p/shell {:timeout 50 :out :string} ["sleep" "10"])))
    (is (< (/ (- (System/nanoTime) start) 1e6) 2000))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'tools-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
