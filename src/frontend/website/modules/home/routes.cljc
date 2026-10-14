(ns website.modules.home.routes
  "Complete native component destinations for the landing page's public sections."
  #?(:cljs (:require [website.react :as r])))

(def targets
  {:intro "intro"
   :services "section-services"
   :moneyshot "moneyshot"
   :story "about-intro"
   :interlude "interlude-0"
   :soundcloud "soundcloud"
   :gallery "gallery"})

(def spec
  (mapv (fn [[section id]]
          (let [path (str "/section/" (name section))]
            [path {:name (keyword "home-section" (name section))
                   :document-target id
                   #?@(:cljs [:controllers [{:start (fn [_] (r/dispatch [:navigation/activate-component]))}]])
                   :component-route {:module :home
                                     :view :page
                                     :action (fn [_] [:scroll/component-target id path])}}]))
        (sort-by key targets)))
