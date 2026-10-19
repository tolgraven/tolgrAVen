(ns website.highlight-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as string]
            [website.react :as r]
            [website.subs]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.modules.highlight.code-block :as code-block]
            [website.modules.highlight.module :as highlight]
            [website.modules.highlight.views :as highlight-view]
            [website.modules.markdown.views :as code]
            [website.modules.markdown.interface :as markdown]
            [website.modules.blog.presentation :as blog]
            [website.component.restore :as restore]
            [website.test-support :as support]
            [website.render-context :as context]))

(deftest renderer-options-update-without-remounting-code-controls
  (async done
    (let [element (.createElement js/document "div")
          before @restore/*context
          *root (atom nil)
          text "```clojure\n(inc 1)\n```"]
      (reset! restore/*context {:hydrate? false})
      (-> (go-promise
            (reset! *root (await! (support/create-root! element)))
            (await! (support/render! @*root [code/<parse-markdown-components> text
                                           {:default-language "clojure"}]))
            (await! (support/wait-for! #(.querySelector element ".code-block pre code span")))
            (let [pre (.querySelector element "pre")
                  token (.querySelector element "code span")
                  wrap (.querySelector element ".code-wrap")]
              (.click wrap)
              (await! (support/wait-for! #(.querySelector element ".code-block-wrapped")))
              (await! (support/render! @*root [code/<parse-markdown-components> text
                                             {:default-language "json"}]))
              (is (identical? pre (.querySelector element "pre")))
              (is (identical? token (.querySelector element "code span")))
              (is (.querySelector element ".code-block-wrapped")
                  "Changing parser options preserves the reader's control state")
              (is (= "true" (.getAttribute wrap "aria-pressed")))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (when @*root (support/unmount! @*root))
                      (reset! restore/*context before)
                      (done)))))))

(deftest shared-code-boundary-renders-full-language-support-during-ssr
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (r/render-to-string
                 [code-block/<code-block> "(defn hello [] :world)" :language "clojure"])]
      (is (.includes html "<code"))
      (is (.includes html "<span"))
      (is (.includes html "defn")))))

(deftest language-selection-preserves-tags-and-keeps-fallback-local
  (doseq [tag ["clj" "cljs" "cljc" "clojurescript"]]
    (let [tree (highlight-view/code-tree "(defn yada [] [])" tag "javascript")]
      (is (= "clojure" (some-> tree .-data .-language)) (str "Explicit " tag " uses its grammar"))
      (is (pos? (.-length (.-children tree))))))
  (is (= "cpp" (some-> (highlight-view/code-tree "int answer = 42;" "cpp" "clojure") .-data .-language)))
  (is (= "clojure" (some-> (highlight-view/code-tree "(defn greet [x] (inc x))" nil nil) .-data .-language)))
  (is (= "clojure" (some-> (highlight-view/code-tree "42" nil "clojure") .-data .-language)))
  (is (nil? (some-> (highlight-view/code-tree "42" nil nil) .-data .-language)))
  (is (= "clojure" (some-> (highlight-view/code-tree "(inc 1)" "unknown-tag" "clojure") .-data .-language))))

(deftest untagged-blocks-and-inline-snippets-render-highlighted-during-ssr
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (r/render-to-string
                 [code/<parse-markdown-components>
                  "Inline `42` and `(inc 1)`. <code class=\"language-clojure\">(inc 2)</code>\n\n```\n(defn greet [x] (inc x))\n```\n\n```cpp\nint answer = 42;\n```"
                  {:default-language "clojure"
                   :allow-raw? true}])
          container (.createElement js/document "div")]
      (set! (.-innerHTML container) html)
      (is (= 2 (.-length (.querySelectorAll container ".code-block"))))
      (is (= 3 (.-length (.querySelectorAll container "p > .code-snippet > code.code-highlight"))))
      (is (= 0 (.-length (.querySelectorAll container "p pre, pre pre, pre div"))))
      (is (.querySelector container "pre code.language-clojure span"))
      (is (.querySelector container "pre code.language-cpp span"))
      (is (string/includes? html "color:#bd979d")))))

(deftest blog-detection-avoids-lisp-family-false-positives
  (let [text "(rf/reg-event-fx :blog/edit-post\n  (fn [_ [_ post]]\n    {:dispatch-n [[:form-field/clear] [:state [:blog :editing] post]]}))"]
    (is (= "clojure" (some-> (highlight-view/code-tree text nil "clojure" blog/code-languages) .-data .-language)))
    (is (= "elixir" (some-> (highlight-view/code-tree "def hello, do: :world" "elixir" "clojure" blog/code-languages) .-data .-language))
        "An explicit tag can still select a language outside the detection subset")))

(deftest explicit-nil-options-preserve-the-neutral-markdown-api
  (binding [context/*server?* true]
    (doseq [options [nil {:code-options nil}]]
      (let [html (r/render-to-string [code/<parse-markdown-components> "Plain text" options])]
        (is (string/includes? html "Plain text"))
        (is (not (string/includes? html "component-error")))))))

(deftest trusted-raw-pre-retains-attributes-and-whitespace-alongside-markdown-blocks
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (r/render-to-string
                 [code/<parse-markdown-components>
                  "<pre id=\"raw-pre\" class=\"custom-pre\" title=\"Spacing\">first\n  second\n</pre>\n\n<pre id=\"raw-code\" data-tolgraven-code-block=\"true\"><code>(inc 1)</code></pre>\n\n```clojure\n(inc 2)\n```"
                  {:allow-raw? true
                   :default-language "clojure"}])
          container (.createElement js/document "div")]
      (set! (.-innerHTML container) html)
      (let [plain (.querySelector container "pre#raw-pre")
            raw-code (.querySelector container "pre#raw-code")]
        (is (some? plain))
        (is (= "custom-pre" (some-> plain .-className)))
        (is (= "Spacing" (some-> plain (.getAttribute "title"))))
        (is (= "first\n  second\n" (some-> plain .-textContent)))
        (is (.querySelector raw-code "code span")))
      (is (= 1 (.-length (.querySelectorAll container ".code-block"))))
      (is (.querySelector container ".code-block pre code.language-clojure span"))
      (is (zero? (.-length (.querySelectorAll container "pre pre, pre div"))))
      (is (= "true" (.getAttribute (.querySelector container "#raw-code") "data-tolgraven-code-block"))))))

(deftest code-presentation-options-apply-during-ssr-and-inline-stays-small
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (r/render-to-string
                 [code/<parse-markdown-components>
                  "Inline `echo \"hello\"`.\n\n```clojure\n(inc 1)\n(inc 2)\n(inc 3)\n```"
                  {:code-options {:line-numbers? true
                                  :starting-line-number 7
                                  :foldable? true
                                  :folded? true
                                  :fold-lines 2}}])
          container (.createElement js/document "div")]
      (set! (.-innerHTML container) html)
      (is (.querySelector container ".code-block-folded"))
      (is (= "Show all 4 lines" (.getAttribute (.querySelector container "button[aria-expanded=false]") "aria-label")))
      (is (= ["7" "8" "9" "10"]
             (mapv #(.-textContent %) (array-seq (.querySelectorAll container ".react-syntax-highlighter-line-number")))))
      (is (.querySelector container ".code-snippet .code-copy[aria-label='Copy code']"))
      (is (.querySelector container ".code-snippet code span[style]"))
      (is (not (.querySelector container ".code-snippet pre, .code-snippet .react-syntax-highlighter-line-number")))
      (is (= "echo \"hello\"" (.-textContent (.querySelector container ".code-snippet code")))))))

(deftest code-options-share-map-and-keyword-contracts
  (is (m/validate (dsl/->malli code-block/args-schema) ["code" {:line-numbers? true :fold-lines 3}]))
  (is (m/validate (dsl/->malli code-block/args-schema) ["code" :line-numbers? true :fold-lines 3]))
  (is (m/validate (dsl/->malli code-block/args-schema) ["code"]))
  (is (m/validate (dsl/->malli code-block/args-schema) ["code" :style {:hljs {:color "red"}}]))
  (is (m/validate (dsl/->malli code-block/args-schema) ["code" :style #js {:hljs #js {:color "red"}}]))
  (is (not (m/validate (dsl/->malli code-block/args-schema) ["code" :fold-lines 0])))
  (is (not (m/validate (dsl/->malli code-block/args-schema) ["code" {:starting-line-number -1}])))
  (is (not (m/validate (dsl/->malli code-block/args-schema) ["code" :line-numbers? "true"]))))

(deftest linked-inline-code-retains-navigation-without-nested-controls
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (r/render-to-string
                 [code/<parse-markdown-components> "[`name`](https://example.com) and `standalone`"])
          container (.createElement js/document "div")]
      (set! (.-innerHTML container) html)
      (is (= "https://example.com" (.getAttribute (.querySelector container "a") "href")))
      (is (= "name" (.-textContent (.querySelector container "a code"))))
      (is (nil? (.querySelector container "a button")))
      (is (= 1 (.-length (.querySelectorAll container ".code-copy")))))))

(deftest normal-markdown-wrapper-forwards-code-presentation-options
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec
                              :markdown {:view {:parse code/<parse-markdown-components>}}}]
    (let [html (r/render-to-string
                 [markdown/<md->div> "```clojure\n(inc 1)\n(inc 2)\n```"
                  {:code-options {:line-numbers? true
                                  :wrap? true
                                  :foldable? true
                                  :folded? true
                                  :fold-lines 1}}])
          container (.createElement js/document "div")]
      (set! (.-innerHTML container) html)
      (is (.querySelector container ".code-block-wrapped.code-block-folded"))
      (is (.querySelector container ".react-syntax-highlighter-line-number")))))

(deftest native-token-rendering-preserves-multiline-text-and-numbered-attributes
  (let [source "/* first\n  second <script>& */\nconst x = \"λ\";"
        html (r/render-to-string
               [highlight-view/<formatted-code> source
                :language "javascript"
                :line-numbers? true
                :starting-line-number 9
                :line-props (fn [number] {:data-line number})])
        container (.createElement js/document "div")]
    (set! (.-innerHTML container) html)
    (let [code (.querySelector container "pre > code")
          numbers (array-seq (.querySelectorAll code ".react-syntax-highlighter-line-number"))]
      (is (= ["9" "10" "11"] (mapv #(.-textContent %) numbers)))
      (is (= 3 (.-length (.querySelectorAll code "[data-line]"))))
      (is (nil? (.querySelector code "script")) "Code text cannot become raw HTML")
      (doseq [number numbers] (.remove number))
      (is (= source (.-textContent code)) "Multiline tokens retain every whitespace/codepoint")
      (is (string/includes? html "color:#908682") "Comment tokens retain Bruvbox"))))
