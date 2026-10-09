(ns tolgraven.highlight-test
  (:require [cljs.test :refer-macros [deftest is]]
            [reagent.dom.server :as server]
            [tolgraven.components.markdown :as markdown]
            [tolgraven.modules.highlight.module :as highlight]
            [tolgraven.render-context :as context]))

(deftest shared-code-boundary-renders-full-language-support-during-ssr
  (binding [context/*server?* true
            context/*modules* {:highlight highlight/spec}]
    (let [html (server/render-to-string
                 [markdown/<code-block> "(defn hello [] :world)" :language "clojure"])]
      (is (.includes html "<code"))
      (is (.includes html "<span"))
      (is (.includes html "defn")))))
