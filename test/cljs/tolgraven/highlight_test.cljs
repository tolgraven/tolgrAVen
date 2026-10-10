(ns tolgraven.highlight-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as string]
            [reagent.dom.server :as server]
            [malli.core :as m]
            [tolgraven.components.code-block :as code-block]
            [tolgraven.components.markdown :as markdown]
            [tolgraven.modules.highlight.module :as highlight]
            [tolgraven.modules.highlight.views :as highlight-view]
            [tolgraven.components.ui.code :as code]
            [tolgraven.modules.blog.views :as blog]
            [tolgraven.render-context :as context]))

(deftest shared-code-boundary-renders-full-language-support-during-ssr
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (server/render-to-string
                 [markdown/<code-block> "(defn hello [] :world)" :language "clojure"])]
      (is (.includes html "<code"))
      (is (.includes html "<span"))
      (is (.includes html "defn")))))

(deftest language-selection-preserves-tags-and-keeps-fallback-local
  (is (= "cpp" (.-language (highlight-view/code-tree "int answer = 42;" "cpp" "clojure"))))
  (is (= "clojure" (.-language (highlight-view/code-tree "(defn greet [x] (inc x))" nil nil))))
  (is (= "clojure" (.-language (highlight-view/code-tree "42" nil "clojure"))))
  (is (nil? (.-language (highlight-view/code-tree "42" nil nil))))
  (is (= "clojure" (.-language (highlight-view/code-tree "(inc 1)" "unknown-tag" "clojure")))))

(deftest untagged-blocks-and-inline-snippets-render-highlighted-during-ssr
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (server/render-to-string
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
    (is (= "clojure" (.-language (highlight-view/code-tree text nil "clojure" blog/code-languages))))
    (is (= "elixir" (.-language (highlight-view/code-tree "def hello, do: :world" "elixir" "clojure" blog/code-languages)))
        "An explicit tag can still select a language outside the detection subset")))

(deftest explicit-nil-options-preserve-the-neutral-markdown-api
  (binding [context/*server?* true]
    (let [html (server/render-to-string [code/<parse-markdown-components> "Plain text" nil])]
      (is (string/includes? html "Plain text"))
      (is (not (string/includes? html "component-error"))))))

(deftest trusted-raw-pre-retains-attributes-and-whitespace-alongside-markdown-blocks
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (server/render-to-string
                 [code/<parse-markdown-components>
                  "<pre id=\"raw-pre\" class=\"custom-pre\" title=\"Spacing\">first\n  second\n</pre>\n\n<pre id=\"raw-code\"><code>(inc 1)</code></pre>\n\n```clojure\n(inc 2)\n```"
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
      (is (not (string/includes? html "data-tolgraven-code-block"))))))

(deftest code-presentation-options-apply-during-ssr-and-inline-stays-small
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (server/render-to-string
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
      (is (= "Show all 4 lines" (.-textContent (.querySelector container "button[aria-expanded=false]"))))
      (is (= ["7" "8" "9" "10"]
             (mapv #(.-textContent %) (array-seq (.querySelectorAll container ".react-syntax-highlighter-line-number")))))
      (is (.querySelector container ".code-snippet .code-copy[aria-label='Copy code']"))
      (is (.querySelector container ".code-snippet code span[style]"))
      (is (not (.querySelector container ".code-snippet pre, .code-snippet .react-syntax-highlighter-line-number")))
      (is (= "echo \"hello\"" (.-textContent (.querySelector container ".code-snippet code")))))))

(deftest code-options-share-map-and-keyword-contracts
  (is (m/validate code-block/args-schema ["code" {:line-numbers? true :fold-lines 3}]))
  (is (m/validate code-block/args-schema ["code" :line-numbers? true :fold-lines 3]))
  (is (m/validate code-block/args-schema ["code"]))
  (is (m/validate code-block/args-schema ["code" :style {:hljs {:color "red"}}]))
  (is (m/validate code-block/args-schema ["code" :style #js {:hljs #js {:color "red"}}]))
  (is (not (m/validate code-block/args-schema ["code" :fold-lines 0])))
  (is (not (m/validate code-block/args-schema ["code" {:starting-line-number -1}])))
  (is (not (m/validate code-block/args-schema ["code" :line-numbers? "true"]))))
