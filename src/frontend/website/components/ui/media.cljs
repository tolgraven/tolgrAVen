(ns website.components.ui.media
  (:require [website.component.registry]
            [website.macros :as m :include-macros true]))



(m/defc <link-img-title> "Link eith an image and a title, for posts for example"
  [& {:as content
      :keys [title text url side]
      :or {side :left}}]
  (let []
    [:div.float-wrapper
     [:div.caption-inset {:class (str side)}
      [:p text]]
     [:h2 title]]))


(m/register-render-functions!)
