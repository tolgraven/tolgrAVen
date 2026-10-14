(ns website.modules.neovim.page
  (:require [website.component.registry]
            [website.macros :refer-macros [defpage]]
            [website.modules.neovim.views :as views]))

(defpage <page> []
  [:section.neovim-page
   [:h1 "Neovim"]
   [:p "Neovim running locally in your browser. Press i to edit, Esc for normal mode, and :help for help."]
   [views/<editor> {:initial-file "src/frontend/website/modules/neovim/views.cljs", :filetype "clojure"}]
   [:p "This is a temporary browser session. Copy your work before restarting or leaving."]])
