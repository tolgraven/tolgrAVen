(ns website.neovim-http-test
  (:require [clojure.test :refer [deftest is]]
            [website.modules.neovim.http :as http]
            [website.page-router :as pages]
            [website.middleware :as middleware]
            [website.config :as config]
            [website.env :as environment]
            [website.build.packaging :as packaging]
            [clojure.java.io :as io]))

(deftest isolation-is-scoped-to-editor-documents-and-workers
  (let [response {:status 200, :headers {"Content-Type" "text/html"}, :body "editor"}
        handler (http/wrap-isolation (constantly response))]
    (doseq [path ["/neovim" "/neovim/github/u/r/abcdef" "/vendor/neovim/nvim-worker.js" "/vendor/neovim/nvim.wasm"]]
      (let [result (handler {:uri path})]
        (is (= "same-origin" (get-in result [:headers "Cross-Origin-Opener-Policy"])))
        (is (= "require-corp" (get-in result [:headers "Cross-Origin-Embedder-Policy"])))
        (is (= "editor" (:body result)))))
    (doseq [path ["/" "/blog" "/neovim-other" "/vendor/neovim-other/nvim.js"]]
      (is (= response (handler {:uri path}))))
    (is (nil? ((http/wrap-isolation (constantly nil)) {:uri "/neovim"})))))

(deftest disabled-packaging-removes-the-runtime
  (let [original slurp]
    (with-redefs [slurp (fn [source & args]
                        (if (= "resources/site.edn" (str source))
                          "{:modules {:neovim {:enabled false}}}"
                          (apply original source args)))]
      (let [patterns (:exclusions (packaging/plan))]
        (is (some #(re-find % "public/vendor/neovim/nvim.wasm") patterns))
        (is (some #(re-find % "website/modules/neovim/http.clj") patterns))
        (is (not-any? #(re-find % "public/img/logo.svg") patterns))))))

(deftest isolation-survives-the-real-middleware-stack
  (with-redefs [config/env {:test true}
                middleware/wrap-optimus identity
                environment/defaults {:middleware identity}]
    (let [handler (middleware/wrap-base
                    (constantly {:status 200, :headers {"Content-Type" "text/html"}, :body "editor"}))
          request {:uri "/neovim"
                   :request-method :get
                   :scheme :http
                   :server-name "localhost"
                   :server-port 4000
                   :headers {}}
          response (handler request)]
      (is (= 200 (:status response)))
      (is (= "same-origin" (get-in response [:headers "Cross-Origin-Opener-Policy"])))
      (is (= "require-corp" (get-in response [:headers "Cross-Origin-Embedder-Policy"])))
      (is (nil? (get-in (handler (assoc request :uri "/ordinary"))
                       [:headers "Cross-Origin-Opener-Policy"]))))))

(deftest github-diff-routes-consume-typed-immutable-revisions
  (let [sha (apply str (repeat 40 "a"))
        match (pages/request-match (str "/neovim/github/tolgraven/tolgrAVen/" sha) {})]
    (is (= :github-diff (get-in match [:data :name])))
    (is (= {:user "tolgraven", :repo "tolgrAVen", :sha sha} (get-in match [:parameters :path])))
    (is (thrown? Exception (pages/request-match "/neovim/github/tolgraven/tolgrAVen/HEAD" {})))))
