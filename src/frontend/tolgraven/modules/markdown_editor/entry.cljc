(ns tolgraven.modules.markdown-editor.entry
  #?(:cljs (:require [tolgraven.component.registry]
                     [tolgraven.macros :refer-macros [defc]]
                     #?(:browser [tolgraven.modules.markdown-editor.views :as rich]))))

#?(:cljs
   (defc <editor>
     "Browser-only rich editor; the server keeps the source form's stable fallback."
     [spec]
     #?(:browser [rich/<editor> spec]
        :default [:div.md-editor-loading "Preparing visual editor…"])))
