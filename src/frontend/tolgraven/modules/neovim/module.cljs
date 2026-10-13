(ns tolgraven.modules.neovim.module
  (:require [tolgraven.modules.neovim.pages :as pages]
            [tolgraven.modules.neovim.views :as views]
            [tolgraven.modules.neovim.page :as page]))

(def spec
  {:id :neovim
   :styles ["/css/tolgraven/modules/neovim.min.css"]
   :pages pages/spec
   :view {:editor #'views/<editor>
          :page #'page/<page>}})
