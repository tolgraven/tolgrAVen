(ns website.modules.markdown.interface
  "Explicit deferred Markdown rendering, with the caller's trust and media options."
  (:require [website.component.registry]
            [website.util :as util :refer [at]]
            [website.macros :as m :include-macros true]
            [website.loader :as loader]))

(defn <parse-markdown-components> [text & options]
  (loader/component-vector
    {:module :markdown, :view :parse, :load-on :immediate, :<loading> [:pre text]}
    (into [text] options)))


(m/defc <md->div> [md & [options]]
  ;; The containing component owns appearance. A separate hidden first commit
  ;; here flashes cached text when an already-visible article is remounted.
  [:div.md-rendered
   [<parse-markdown-components>
    (util/md->normal md)
    {:allow-images? (= :trusted (:trust options))
     :allow-raw? (= :trusted (:trust options))
     :image-assets (:image-assets options)
     :image-readiness (:image-readiness options)
     :default-language (:default-language options)
     :auto-languages (:auto-languages options)
     :code-options (:code-options options)}]])

(m/register-render-functions!)
