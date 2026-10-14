(ns website.components.ui.overlays
  (:require [website.component.registry]
            [website.react :as r]
            [website.util :as util :refer [at]]
            [website.macros :as m :include-macros true]
            [website.components.ui.controls :as controls]))


(m/defc <zoom-to-modal> "E.g. bring up image to semi-fullscreen when clicked.
                     This version just generically zooms, would be nice if animated from current pos.
                     Would need to grab actual pos and size from DOM for that..."
  ; TODO should also function as gallery if passing multiple elements
  ; TODO stop scroll events while active
  [id]
  (when-let [data @(r/subscribe [:state [:modal-zoom id]])]
    (let [component (:component data)
          opened (and (:opened data)
                      (:loaded data))
          on-click #(do (.stopPropagation %)
                        (r/dispatch [:modal-zoom id :close]))] ;actually guess container will handle click on img too. but later if add buttons etc, nyah
      [:div.modal-bg.center-content
       {:class (when opened "modal-bg-open")
        :on-click on-click
        :style {:overflow "hidden"}} ; prevent scrolling background
       [:div#modal.modal-zoom.center-content
        {:class (when opened "modal-zoomed")
         ; :on-click on-click
         :ref #(when % (r/dispatch [:modal-zoom id :loaded])) } ;to apply things only later
        [controls/<close> on-click]
        (util/add-attrs component
                        {:class "modal-zoomed-item"}) ]])))

(m/defc <modal> "Container for anything modal, taking care of common stuff. USE FOR COOKIE NOTICE LOL YES"
 [component & [on-outside-click]] ; and path for when to show...
 (let []
  [:div#modal-container
   {:class (when @(r/subscribe [:state [:modal]]) "modal-is-open")}
   [:div#modal-bg {:on-click (or on-outside-click
                                 #(r/dispatch [:state [:modal] false]))}]
   [:div#modal ;{:class (when @(r/subscribe [:state [:modal]]) "modal-is-open")}
    component]]))
