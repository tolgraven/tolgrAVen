(ns website.modules.markdown-editor.entry
  #?(:cljs (:require [website.component.registry]
                     [website.macros :refer-macros [defc]]
                     #?(:browser [website.modules.markdown-editor.views :as rich])
                     #?(:browser [website.modules.markdown-editor.authoring :as authoring]))))

#?(:cljs
   (defc <editor>
     "Browser-only rich editor; the server keeps the source form's stable fallback."
     [spec]
     #?(:browser [rich/<editor> spec]
        :default [:div.md-editor-loading "Preparing visual editor…"])))

#?(:cljs
   (defc <composer>
     "Shared form/upload owner and visual/source controls; capabilities come from the caller."
     [spec]
     #?(:browser [authoring/<markdown-editor> spec]
        :default [:div.md-editor-loading "Preparing visual editor…"])))
