(ns website.modules.blog.avatar
  "Keep the native avatar footprint while its optional account module loads."
  (:require [website.loader]
            [website.macros :as m]))

(defn <avatar> [user & [extra-class]]
  (m/<>
    {:module :user
     :view :avatar
     :<loading> [:div.user-avatar-container
                 [:span {:class (str "user-avatar " extra-class)
                         :aria-hidden true
                         :style {:visibility "hidden"}}]]}
    user extra-class))
