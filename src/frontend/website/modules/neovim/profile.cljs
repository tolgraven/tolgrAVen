(ns website.modules.neovim.profile
  "Pinned plugin files are installed by Emscripten preRun, before the RPC loop.")

(def bundle-schema
  [:map
   [:version [:= 1]]
   [:sources map?]
   [:files [:vector
            [:map
             [:path [:string {:min 1}]]
             [:text string?]
             [:sha256 [:string {:min 64, :max 64}]]]]]])

(def workspace-schema
  [:map
   [:version [:= 1]]
   [:root [:= "tolgrAVen"]]
   [:files [:vector [:map [:path string?] [:text string?]]]]])

(defn configure! [request!]
  (request! "nvim_exec_lua"
    ["local root = '/home/user/.config/nvim/wasm'; vim.cmd('helptags ' .. root .. '/metabuffer/doc'); dofile(root .. '/init.lua'); vim.cmd('cd /workspace/tolgrAVen')"
     []]))
