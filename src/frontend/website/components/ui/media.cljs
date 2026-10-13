(ns website.components.ui.media
  (:require [website.component.registry]
            [website.loader.view :as loader-view]
            [website.macros :as m :include-macros true]))


(defn <float-img> [& args]
  (loader-view/form :home :float-image (vec args) nil))

(defn <auto-layout-text-imgs> [content]
  (loader-view/form :home :text-images [content] nil))


(m/defc <link-img-title> "Link eith an image and a title, for posts for example"
  [& {:as content
      :keys [title text url side]
      :or {side :left}}]
  (let []
    [:div.float-wrapper
     [:div.caption-inset {:class (str side)}
      [:p text]]
     [:h2 title]]))


(defn <carousel-idx-btns> [& args]
  (loader-view/form :carousel :indices (vec args) nil))

(defn <carousel> [& args]
  (loader-view/form :carousel :three (vec args) nil))

(defn <carousel-normal> [& args]
  (loader-view/form :carousel :normal (vec args) nil))

(m/register-render-functions!)
