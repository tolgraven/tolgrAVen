(ns tolgraven.views.auto
  (:require
    [tolgraven.component.registry]
    [tolgraven.react :as rf]
    [tolgraven.component :as component]
    [tolgraven.content.contract :as content-contract]
    [tolgraven.ssr.contract :as ssr-contract]
    [tolgraven.components.home :as home]
    [tolgraven.components.media :as media]
    [tolgraven.components.oembed :as oembed]
    [tolgraven.loader :as l]
    [tolgraven.macros :as m]
    [tolgraven.ui :as ui]))

(declare sections)

(m/defc <run-init>
  [section & [init dep]]
  (let [spec (get sections section)
        event (or init (:init spec) [:scope/init section])
        dependency (or dep (:dep spec))]
    [ui/<lazy-load> [:on-booted dependency event]]))

(def sections
  {:intro       {:<comp> home/<intro>
                 :content :intro}
   :services    {:<comp> home/<services>
                 :content :services
                 :init [:state [:services :to-focus?] true]}
   :moneyshot   {:<comp> media/<moneyshot>
                 :content :moneyshot}
   :story       {:<comp> home/<story>
                 :content :story}
   :gallery     {:<comp> media/<gallery>
                 :content :gallery
                 :dep :site
                 :init [:state [:gallery :loaded] true]}
   :soundcloud  {:<comp> oembed/<soundcloud>
                 :content-deps [:soundcloud]
                 :dep :site
                 :init [:booted :soundcloud]}
   :strava      {:module :strava
                 :dep :store}
   :instagram   {:module :instagram
                 :dep :store}
   :github      {:module :github
                 :dep :site}
   :gpt         {:module :gpt}
   :chat        {:module :chat}
   :interlude   {:<comp> media/<interlude>
                 :content :interlude}
   :init        {:<comp> <run-init>}})

; will want triggering all things to init
; when loading page halfway down so scroll pos stays correct
; so no lazy then
; will need to put sections in db and make a sub like
; (->> sections vals (map :init) (filter some?))
; and event
; (doall run/init @sub)

(def layouts
  {:main ssr-contract/landing-layout
   :desktop :something-splitty
   :joen :just-about-me/components
   :av :just-about-company })


(m/defc <get-component> "Get component, and its init event runner, if any."
  [id section-map]
  (let [{:keys [module <comp> <loading> content content-deps args dep init]} section-map
        view (if module
               [l/<> {:module module :view (or <comp> :view)
                      :defer? true :<loading> <loading>}]
               [<comp>])]
    [:<>
     [component/<prefetch> (into (vec (:depends section-map))
                                (when-let [keys (seq (or content-deps (if content [content] (get content-contract/module-content module []))))]
                                  [{:source :strapi :keys (vec keys)}]))]
     (when (or init module)
       [<run-init> id init dep])
     (if (and content (nil? @(rf/subscribe [:content [content]])))
       [:div.loading-container [:div.loading-spinner]]
       (cond-> view
         content (conj @(rf/subscribe [:content [content]]))
         args    (conj args)
         true    vec))]))

(m/defc <get-section> "Get a section, from either a vector (with args) or a straight keyword"
  [section]
  (if (vector? section)
    (let [[id args] section]
      [<get-component> id (merge (get sections id)
                                {:args args})])
    [<get-component> section (get sections section)]))

;; Keep these nested error-boundary experiments with the layout they exercise.
(macroexpand '(m/defc <test-2>
  "test-comp-2"
  {:features [:error-boundary]}
  [spec]
  [:div "goodbye" (throw (js/Error. "test2"))]))
(m/defc <test-2>
  "test-comp-2"
  {:features [:error-boundary]}
  [spec]
  [:div "goodbye" (throw (js/Error. "test2"))])
(m/defc <test>
  "test-comp"
  {:features [:error-boundary]}
  [spec]
  [:div "hello" spec [<test-2> spec] ])

(m/defc <auto> "Present main page UI. Should come from data structure.
               Should auto lazy load/init all components with such functionality at point,
               apart from the separate lazy loading done before-hand (if loads in middle of page etc)"
  []
  [:<>
   ; [<test> {:wah "cool"}]
   (for [[i component] (map-indexed vector (layouts :main))]
     ^{:key i}
     [<get-section> component])])
