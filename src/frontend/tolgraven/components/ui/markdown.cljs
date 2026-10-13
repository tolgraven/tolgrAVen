(ns tolgraven.components.ui.markdown
  (:require [tolgraven.component.registry]
            [tolgraven.util :as util :refer [at]]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.components.markdown :as code]))


(m/defc <md->div> [md & [options]]
  ;; The containing component owns appearance. A separate hidden first commit
  ;; here flashes cached text when an already-visible article is remounted.
  [:div.md-rendered
   [code/<parse-markdown-components>
    (util/md->normal md)
    {:allow-images? (= :trusted (:trust options))
     :allow-raw? (= :trusted (:trust options))
     :image-assets (:image-assets options)
     :default-language (:default-language options)
     :auto-languages (:auto-languages options)
     :code-options (:code-options options)}]])
