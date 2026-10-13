(ns tolgraven.modules.neovim.page
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defpage]]
            [tolgraven.modules.neovim.views :as views]))

(defpage <page> []
  [:section.neovim-page
   [:h1 "Neovim"]
   [:p "Neovim running locally in your browser. Press i to edit, Esc for normal mode, and :help for help."]
   [views/<editor> {:initial-file "src/frontend/tolgraven/modules/neovim/views.cljs", :filetype "clojure"}]
   [:p "This is a temporary browser session. Copy your work before restarting or leaving."]])
