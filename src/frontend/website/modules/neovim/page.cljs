(ns website.modules.neovim.page
  (:require [website.component.registry]
            [website.macros :refer-macros [defpage]]
            [website.modules.neovim.views :as views]))

(defpage <page> []
  [:section.neovim-page
   [views/<editor>
    {:initial-file "src/frontend/website/modules/neovim/views.cljs"
     :filetype "clojure"
     :rows 36
     :menu-content [:a {:href "/", :data-document-navigation "true"} "Back to site"]}]])
