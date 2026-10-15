(ns test.ssr
  "Exercise all page kinds through the one Node renderer; generate hydration fixtures."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [util.process :refer [shell]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [website.modules.blog.data :as blog-data]
            [website.supabase.plan :as plan]
            [website.supabase.query :as query]))

(defn check! [condition message]
  (when-not condition (throw (ex-info message {}))))

(defn render! [worker snapshots]
  (let [result (shell {:in (str (str/join "\n" (map json/generate-string snapshots)) "\n")
                                :out :string :err :string :timeout 15000}
                               ["node" worker])
        responses (mapv #(json/parse-string % true) (str/split-lines (:out result)))]
    (check! (not (re-find #"Subscribe was called outside|localStorage is not available" (:err result)))
            "Renderer accessed browser-only state")
    (check! (and (= (count snapshots) (count responses))
                 (every? #(and (:html %) (map? (:module-views %))) responses))
            "Node worker did not return all pages")
    responses))

(defn fixture! [name snapshot response]
  (fs/create-dirs "resources/public/js/tests/js")
  (spit (str "resources/public/js/tests/js/" name "-ssr.json")
        (json/generate-string {:snapshot (assoc snapshot :module-views (:module-views response))
                               :html (:html response)})))

(defn contains-all? [html strings] (every? #(str/includes? html %) strings))

(defn comment-snapshot [blog standalone?]
  (let [post (dissoc (first (:posts blog)) :author :date)
        comment (fn [id parent path ts text replies]
                  {:id id
                   :parent-post (:id post)
                   :parent-comment parent
                   :path path
                   :ts ts
                   :user (:user post)
                   :text text
                   :reply-count replies})
        root (comment "old-root" nil [42] 0 "Old root outside the first page" 1)
        child (comment "old-child" "old-root" [42 "old-root"] 1 "Deep ancestor" 1)
        target (comment "113" "old-child" [42 "old-root" "old-child"] 2 "Selected comment body" 1)
        descendant (comment "114" "113" [42 "old-root" "old-child" "113"] 3 "Selected descendant body" 0)
        neighbors (mapv #(comment (str "neighbor-" %) nil [42] (+ 100 %) "Neighboring comment" 0) (range 20))
        contract {"blog-posts" {42 post}
                  "blog-comments" (into {} (map (juxt :id identity)) (concat [root child target descendant] neighbors))
                  "users" {(:user post) (:author (first (:posts blog)))}}
        selection (cond-> {:comment-id "113"}
                    standalone? (assoc :standalone-comment? true)
                    (not standalone?) (assoc :post-id 42))
        result (plan/evaluate blog-data/plan selection
                 #(mapv (fn [opts] (or (query/query-contract contract opts) {:docs []})) %))]
    (check! (:ready? result) "Comment fixture plan did not finish")
    (merge (select-keys blog [:renderer-version :kind :content])
           (blog-data/snapshot result 1000)
           {:path (if standalone? "/blog/comment/113" "/blog/post/42/comment/113")})))

(defn -main [& args]
  (let [{:keys [worker]} (cli/parse-opts args {:spec {:worker {:default "target/ssr/site.js"}}})
        blog (json/parse-string (slurp "test/browser/blog-ssr-input.json") true)
        other (assoc blog :posts [{:id 99 :title "Second request" :text "Isolated" :tags []}]
                          :summaries [{:id 99 :title "Second request" :tags []}])
        [first second] (render! worker [blog other])
        home {:renderer-version 3 :kind "landing" :path "/" :posts []
              :content (json/parse-string (slurp "resources/content-seed.json") true)}
        [landing blog-again again] (render! worker [home blog home])
        experiment (assoc home :path "/experiments/layout")
        [experiment-html home-after-experiment] (render! worker [experiment home])
        [account] (render! worker [(assoc home :app-db-edn "{:state {:user-section [:login]}}")])
        cv (assoc home :kind "cv" :path "/cv")
        docs (assoc home :kind "docs" :path "/docs"
                         :app-db-edn "{:docs {\"index\" \"<h1>API reference</h1><p>Shared documentation view.</p>\"} :state {:docs {:current-page \"index\"}}}")
        [cv-html docs-html cv-again] (render! worker [cv docs cv])
        missing (assoc blog :path "/blog/post/missing-999999" :post-id 999999
                           :missing? true :posts [] :comments [])
        [missing-html] (render! worker [missing])
        code-blog (update-in blog [:posts 0 :text]
                             str "\n\nInline `42`, `(inc 1)` and `echo \"$HOME\"`.\n\n```\n(defn untagged [x] (inc x))\n```\n\n```clojure\n(defn greet [name] (str \"Hello \" name))\n```\n\n```cpp\n// Retain the language during hydration\nint answer = 42;\n```\n")
        [code-html] (render! worker [code-blog])
        focused-comment (comment-snapshot blog false)
        standalone-comment (comment-snapshot blog true)
        [focused-html standalone-html] (render! worker [focused-comment standalone-comment])
        authored-blog (assoc-in blog [:posts 0 :text]
                               (str "Before authored content\n\n"
                                    "<Component component=\"website.components.content.box/<highlight-box>\" text=\"SSR authored note\" tone=\"tip\" layout-width=\"20rem\" layout-flow=\"right\" />\n\n"
                                    "After authored content\n\n"
                                    "<Component component=\"website.components.content.markdown/<image>\" src=\"/img/tolgrav-square.png\" alt=\"Authored logo\" layout-width=\"10rem\" />\n\n"
                                    "<Component component=\"website.modules.settings.views/<config-table>\" title=\"SSR reader controls\" />\n\n"
                                    "<Component component=\"website.modules.search.views/<inline>\" args='json:[]' />"))
        [authored-html] (render! worker [authored-blog])
        authored-config (assoc-in blog [:posts 0 :text]
                                  "<Component component=\"website.modules.settings.views/<config-table>\" title=\"SSR reader controls\" />")
        [authored-config-html] (render! worker [authored-config])
        shells (render! worker (mapv #(assoc % :shell? true :posts [] :query-params {})
                                     [home (assoc blog :path "/blog/post/42") cv]))]
    (check! (and (contains-all? (:html experiment-html)
                              ["data-layout=\"experiments\"" "experiment-story" "A page assembled from components"
                               "Editing it does not change the home page story." "<aside" "Page contents"])
                 (not (str/includes? (:html experiment-html) "id=\"about\""))
                 (= (:html landing) (:html home-after-experiment)))
            "Dynamic page routing, independent article content, aside index or request isolation failed")
    (check! (contains-all? (:html first) ["<strong>article</strong>" "Server comment 0"
                                         "Server visible reply" "Server comment 4" "5 comments"])
            "Blog content or expanded comments missing")
    (check! (not (re-find #"<script>|javascript:" (:html first))) "Unsafe Markdown output")
    (check! (and (str/includes? (:html second) "Second request")
                 (not (str/includes? (:html second) "Server-rendered blog"))) "Blog request state leaked")
    (check! (and (= (:html landing) (:html again))
                 (contains-all? (:html landing) ["h-intro" "id=\"section-services\"" "id=\"about\""
                                                 "id=\"gallery\"" "id=\"story-image-cljs\""])
                 (not (re-find #"id=\"cljs\"|Server-rendered blog" (:html landing)))
                 (str/includes? (:html blog-again) "Server-rendered blog")
                 (not (str/includes? (:html blog-again) "h-intro"))) "Home/blog state leaked")
    (check! (contains-all? (:html landing) ["animate" "intro-title-letter" "--intro-letter-delay:2000ms"])
            "Initial SSR must include the document entrance and original typing clock")
    (check! (and (not (contains? (:module-views landing) :user))
                 (not (contains? (:module-views landing) :blog))
                 (str/includes? (:html landing) "User account"))
            "Anonymous Home keeps its account link without activating User or Blog")
    (check! (and (some #{"view"} (get-in account [:module-views :user]))
                 (str/includes? (:html account) "user-section-wrapper"))
            "An open account panel retains real SSR markup and hydration ownership")
    (check! (and (= (:html cv-html) (:html cv-again))
                 (contains-all? (:html cv-html) ["cv-skills" "carousel-normal" "carousel-item-main"])
                 (str/includes? (:html docs-html) "Shared documentation view.")
                 (not (str/includes? (:html docs-html) "cv-skills"))) "CV/docs state leaked")
    (check! (str/includes? (str/lower-case (:html missing-html)) "not found") "Missing permalink did not render not-found")
    (check! (contains-all? (:html code-html) ["code-block" "<!--$-->" "language-cpp" "color:#80a0b3" "color:#bd979d" "code-highlight" "untagged" "greet" "Wrap lines"])
            "SSR code must retain highlighted markup in a completed Suspense boundary")
    (check! (not (str/includes? (:html code-html) "<pre><div"))
            "Markdown must not wrap a code block in another pre")
    (check! (not (str/includes? (:html first) "code-block"))
            "Plain blog content must not require the highlighter")
    (check! (contains-all? (:html focused-html)
                          ["Old root outside the first page" "Deep ancestor" "Selected comment body" "id=\"blog-comment-113\""])
            "A focused old thread must render with its ancestors before hydration")
    (check! (and (contains-all? (:html standalone-html)
                               ["Selected comment body" "Selected descendant body" "all its comments" "/blog/post/server-rendered-42"])
                 (not (re-find #"Neighboring comment|Deep ancestor|Old root outside|This <strong>article" (:html standalone-html))))
            "Standalone comment SSR must contain its subtree and parent link without neighbors or full post text")
    (check! (every? #(not (str/includes? (:html %) "component-error")) shells) "A page shell violated its contract")
    (check! (str/includes? (:html (nth shells 0)) "background-image:linear-gradient(to bottom, #")
            "Initial landing skeleton must paint sampled colors before image requests finish")
    (check! (contains-all? (:html authored-html) ["SSR authored note" "content-box" "Authored logo" "SSR reader controls" "role=\"switch\"" "content-search"])
            "Authored components must render their actual public UI in Node SSR")
    (check! (not (re-find #"<p><(?:div|aside)|<site-component|Preparing component" (:html authored-html)))
            "Authored SSR must retain block-safe real component markup")
    (check! (contains? (:module-views authored-config-html) :settings)
            "Visible authored settings must own their initial CSS and hydration inventory")
    (check! (not (contains? (:module-views first) :settings))
            "Hidden settings popup must stay out of plain post first-paint metadata")
    (doseq [[name snapshot response] [["blog" blog first] ["landing" home landing]
                                      ["cv" cv cv-html] ["docs" docs docs-html] ["missing" missing missing-html]
                                      ["code-blog" code-blog code-html] ["authored-blog" authored-blog authored-html]
                                      ["focused-comment" focused-comment focused-html]
                                      ["standalone-comment" standalone-comment standalone-html]
                                      ["authored-config" authored-config authored-config-html]]]
      (fixture! name snapshot response))
    (println "Node SSR: all page kinds, escaping, request isolation, missing permalink and shells passed; hydration fixtures generated.")))
