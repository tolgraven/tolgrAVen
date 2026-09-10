(ns tolgraven.views.auto
  (:require [re-frame.core :as rf]
            [tolgraven.components.home :as home]
            [tolgraven.components.media :as media]
            [tolgraven.components.oembed :as oembed]
            [tolgraven.loader :as l]
            [tolgraven.macros :as m]
            [tolgraven.ui :as ui]))

(declare sections)

(defn run-init
  [section & [init dep]]
  (let [spec (get sections section)
        event (or init (:init spec) [:scope/init section])
        dependency (or dep (:dep spec))]
    [ui/lazy-load [:on-booted dependency event]]))

(def sections
  {:intro       {:<comp> home/intro
                 :content :intro}
   :services    {:<comp> home/services
                 :content :services
                 :init [:state [:services :to-focus?] true]}
   :moneyshot   {:<comp> media/moneyshot
                 :content :moneyshot}
   :story       {:<comp> home/story
                 :content :story}
   :gallery     {:<comp> media/gallery
                 :content :gallery
                 :dep :site
                 :init [:state [:gallery :loaded] true]}
   :soundcloud  {:<comp> oembed/soundcloud
                 :dep :site
                 :init [:booted :soundcloud]}
   :strava      {:module :strava
                 :dep :firebase}
   :instagram   {:module :instagram
                 :dep :firebase}
   :github      {:module :github
                 :dep :site}
   :gpt         {:module :gpt}
   :chat        {:module :chat}
   :interlude   {:<comp> media/interlude
                 :content :interlude}
   :init        {:<comp> run-init}})

; will want triggering all things to init
; when loading page halfway down so scroll pos stays correct
; so no lazy then
; will need to put sections in db and make a sub like
; (->> sections vals (map :init) (filter some?))
; and event
; (doall run/init @sub)

(def layouts
  {:main [:intro
          [:interlude 0]
          :services
          [:init :services] ; just focuses it
          [:interlude 1]
          :moneyshot
          [:init :instagram]
          [:init :strava]
          :story
          [:interlude 2]

          [:init :soundcloud]

          :strava
          [:init :gallery]
          :soundcloud
          :instagram
          :gallery
          :github
          :gpt
          :chat ]
   :desktop :something-splitty
   :joen :just-about-me/components
   :av :just-about-company })


(defn get-component "Get component, and its init event runner, if any."
  [id section-map]
  (let [{:keys [module <comp> <loading> content args dep init]} section-map
        view (if module
               [l/<> {:module module :view (or <comp> :view)
                      :defer? true :<loading> <loading>}]
               [<comp>])]
    [:<>
     (when (or init module)
       [run-init id init dep])
     (cond-> view
       content (conj @(rf/subscribe [:content [content]]))
       args    (conj args)
       true    vec)]))

(defn get-section "Get a section, from either a vector (with args) or a straight keyword"
  [section]
  (if (vector? section)
    (let [[id args] section]
      [get-component id (merge (get sections id)
                                {:args args})])
    [get-component section (get sections section)]))

(defn auto "Present main page UI. Should come from data structure.
               Should auto lazy load/init all components with such functionality at point,
               apart from the separate lazy loading done before-hand (if loads in middle of page etc)"
  []
  [:<>
   (for [[i component] (map-indexed vector (layouts :main))]
     ^{:key i}
     [get-section component])])
