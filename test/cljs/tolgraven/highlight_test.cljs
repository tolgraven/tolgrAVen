(ns tolgraven.highlight-test
  (:require [cljs.test :refer-macros [deftest is]]
            [clojure.string :as string]
            [reagent.dom.server :as server]
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
      (is (= 3 (.-length (.querySelectorAll container "p > code.code-highlight"))))
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
