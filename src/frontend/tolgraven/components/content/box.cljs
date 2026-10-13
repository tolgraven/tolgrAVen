(ns tolgraven.components.content.box
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]))

(defc <highlight-box>
  "A highlighted background box for an aside, tip or warning. Title is optional; text is plain text, and tone selects its accent."
  {:authoring {
               :category "Widgets"
               :defaults {:text "An aside worth noticing", :tone "note"}}
   :spec-schema [:map [:text :string] [:?title :string] [:?tone [:enum "note" "tip" "warning"]]]}
  [{:keys [text title tone]}]
  [:aside.content-box {:class (str "content-box--" (or tone "note"))}
   (when title [:h3 title]) [:p text]])

