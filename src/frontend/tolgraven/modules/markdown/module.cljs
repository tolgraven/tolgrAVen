(ns tolgraven.modules.markdown.module
  (:require [tolgraven.components.ui.code :as code]))

(def spec
  {:id :markdown
   :view {:code-block #'code/<code-block>
          :parse #'code/<parse-markdown-components>}})
