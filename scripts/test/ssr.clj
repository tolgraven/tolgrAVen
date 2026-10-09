(ns test.ssr
  "Exercise all page kinds through the one Node renderer; generate hydration fixtures."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [util.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn check! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn render! [worker snapshots]
  (let [result (shell {:in (str (str/join "\n" (map json/generate-string snapshots)) "\n")
                                :out :string :err :string :timeout 15000}
                               ["node" worker])
        responses (mapv #(json/parse-string % true) (str/split-lines (:out result)))]
    (check! (not (re-find #"Subscribe was called outside|localStorage is not available" (:err result)))
            "Renderer accessed browser-only state")
    (check! (and (= (count snapshots) (count responses)) (every? :html responses))
            "Node worker did not return all pages")
    responses))

(defn fixture! [name snapshot response]
  (fs/create-dirs "resources/public/js/tests/js")
  (spit (str "resources/public/js/tests/js/" name "-ssr.json")
        (json/generate-string {:snapshot snapshot :html (:html response)})))

(defn contains-all? [html strings] (every? #(str/includes? html %) strings))

(defn -main [& args]
  (let [{:keys [worker]} (cli/parse-opts args {:spec {:worker {:default "target/ssr/site.js"}}})
        blog (json/parse-string (slurp "test/browser/blog-ssr-input.json") true)
        other (assoc blog :posts [{:id 99 :title "Second request" :text "Isolated" :tags []}]
                          :summaries [{:id 99 :title "Second request" :tags []}])
        [first second] (render! worker [blog other])
        home {:renderer-version 3 :kind "landing" :path "/" :posts []
              :content (json/parse-string (slurp "resources/content-seed.json") true)}
        [landing blog-again again] (render! worker [home blog home])
        cv (assoc home :kind "cv" :path "/cv")
        docs (assoc home :kind "docs" :path "/docs"
                         :app-db-edn "{:docs {\"index\" \"<h1>API reference</h1><p>Shared documentation view.</p>\"} :state {:docs {:current-page \"index\"}}}")
        [cv-html docs-html cv-again] (render! worker [cv docs cv])
        missing (assoc blog :path "/blog/post/missing-999999" :post-id 999999
                           :missing? true :posts [] :comments [])
        [missing-html] (render! worker [missing])
        code-blog (update-in blog [:posts 0 :text]
                             str "\n\n```clojure\n(defn greet [name] (str \"Hello \" name))\n```\n\n```cpp\n// Retain the language during hydration\nint answer = 42;\n```\n")
        [code-html] (render! worker [code-blog])
        shells (render! worker (mapv #(assoc % :shell? true :posts [] :query-params {})
                                     [home (assoc blog :path "/blog/post/42") cv]))]
    (check! (contains-all? (:html first) ["<strong>article</strong>" "Server comment 0"
                                         "Server visible reply" "Server comment 4" "5 comments"])
            "Blog content or expanded comments missing")
    (check! (not (re-find #"<script>|javascript:" (:html first))) "Unsafe Markdown output")
    (check! (and (str/includes? (:html second) "Second request")
                 (not (str/includes? (:html second) "Server-rendered blog"))) "Blog request state leaked")
    (check! (and (= (:html landing) (:html again))
                 (contains-all? (:html landing) ["h-intro" "id=\"section-services\"" "id=\"about\""
                                                 "id=\"gallery\"" "id=\"story-image-cljs\""])
                 (not (re-find #"intro-letter|id=\"cljs\"|Server-rendered blog" (:html landing)))
                 (str/includes? (:html blog-again) "Server-rendered blog")
                 (not (str/includes? (:html blog-again) "h-intro"))) "Home/blog state leaked")
    (check! (and (= (:html cv-html) (:html cv-again)) (str/includes? (:html cv-html) "cv-skills")
                 (str/includes? (:html docs-html) "Shared documentation view.")
                 (not (str/includes? (:html docs-html) "cv-skills"))) "CV/docs state leaked")
    (check! (str/includes? (str/lower-case (:html missing-html)) "not found") "Missing permalink did not render not-found")
    (check! (contains-all? (:html code-html) ["code-block" "<!--$-->" "language-cpp" "color:#fb4934" "greet" "Wrap lines"])
            "SSR code must retain highlighted markup in a completed Suspense boundary")
    (check! (not (str/includes? (:html code-html) "<pre><div"))
            "Markdown must not wrap a code block in another pre")
    (check! (not (str/includes? (:html first) "code-block"))
            "Plain blog content must not require the highlighter")
    (check! (every? #(not (str/includes? (:html %) "component-error")) shells) "A page shell violated its contract")
    (doseq [[name snapshot response] [["blog" blog first] ["landing" home landing]
                                      ["cv" cv cv-html] ["docs" docs docs-html] ["missing" missing missing-html]
                                      ["code-blog" code-blog code-html]]]
      (fixture! name snapshot response))
    (println "Node SSR: all page kinds, escaping, request isolation, missing permalink and shells passed; hydration fixtures generated.")))
