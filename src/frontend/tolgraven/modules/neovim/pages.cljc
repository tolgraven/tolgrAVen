(ns tolgraven.modules.neovim.pages
  "Portable routes; browser implementation and worker startup stay in the module.")

(def spec
  [["/neovim" {:name :neovim
               :module :neovim
               :page :page
               :ssr true
               :streaming false
               :depends []}]])
