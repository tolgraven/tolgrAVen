(ns tolgraven.components.ui.layout
  (:require [tolgraven.component.registry]
            [tolgraven.react :as r]
            [tolgraven.util :as util :refer [at]]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.components.heading :as heading]))


(m/defc <observe-sticky> "Check if sticky element has stuck."
  [event]
  (let [observer (util/observer (fn [frac]
                                  (r/dispatch [event frac]))
                                {:threshold [1.0]
                                 :root js/document.documentElement
                                 :rootMargin "-1px 0px 0px 0px"})]
    (fn [event]
      [:div
       {:ref (fn [el] (observer el))}])))

(m/defc <inset> [caption nr]
  (let [pos (case (mod nr 4)
              0 "bottom right"  1 "bottom left"  2 "top right"   3 "top left")]
    [:p.caption-inset {:class pos}
     caption]))


(m/defc <fading-bg-heading> [content]
  [heading/<banner> content #(r/dispatch [:common/navigate! %])])

(m/defc <fading> "Hitherto just css but prog gen prob easier in some cases..."
  [& {:keys [fade-to dir content classes]
      :or {fade-to "fade-to-black" dir "light-from-below"}}]
  [:div.fader [:div {:class (str fade-to " " dir " " classes)}]])

(m/defc <with-heading>
  "Standard heading component with fade and title."
  [heading-path component & [override]]
  [:<>
   [<fading-bg-heading> (merge {} @(r/subscribe [:content heading-path])
                                override)]
   component])
