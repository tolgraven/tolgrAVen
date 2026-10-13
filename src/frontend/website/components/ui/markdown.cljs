(ns website.components.ui.markdown
  (:require [website.component.registry]
            [website.util :as util :refer [at]]
            [website.macros :as m :include-macros true]
            [website.modules.markdown.interface :as code]))


(m/defc <md->div> [md & [options]]
  ;; The containing component owns appearance. A separate hidden first commit
  ;; here flashes cached text when an already-visible article is remounted.
  [:div.md-rendered
   [code/<parse-markdown-components>
    (util/md->normal md)
    {:allow-images? (= :trusted (:trust options))
     :allow-raw? (= :trusted (:trust options))
     :image-assets (:image-assets options)
     :image-readiness (:image-readiness options)
     :default-language (:default-language options)
     :auto-languages (:auto-languages options)
     :code-options (:code-options options)}]])
