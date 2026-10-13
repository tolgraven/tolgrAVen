(ns website.modules.markdown.interface
  #?(:cljs (:require-macros [website.macros :refer [register-render-functions!]]))
  #?(:cljs (:require [website.component.registry]
                     [website.loader :as loader])))

#?(:cljs
   (do
     (defn <code-block> [code & options]
       (let [settings (if (map? (first options)) (first options) (apply hash-map options))]
         (loader/component-vector
           {:module :markdown
            :view :code-block
            :load-on :immediate
            :<loading> (if (:inline? settings) [:code code] [:pre [:code code]])}
           (into [code] options))))
     (defn <parse-markdown-components> [text & options]
       (loader/component-vector
         {:module :markdown, :view :parse, :load-on :immediate, :<loading> [:pre text]}
         (into [text] options)))))

#?(:cljs (register-render-functions!))
