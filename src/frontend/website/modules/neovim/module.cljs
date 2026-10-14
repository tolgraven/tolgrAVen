(ns website.modules.neovim.module
  (:require [website.modules.neovim.pages :as pages]
            [website.modules.neovim.views :as views]
            [website.modules.neovim.page :as page]))

(def spec
  {:pages pages/spec
   :view {:editor #'views/<editor>
          :page #'page/<page>}})
