(ns website.modules.home.views
  (:require
    [website.component.registry]
    [clojure.string :as string]
    [website.react :as r]
    [website.breakpoints]
    [website.modules.main.layout :as layout]
    [website.page-layout :as page-layout]
    [website.site :as site]
    [website.modules.home.sections :as home]
    [website.modules.home.routes :as routes]
    [website.components.media :as media]
    [website.components.oembed :as oembed]
    [website.loader :as l]
    [website.macros :as m]))

(declare sections)

(m/defc <run-init>
  {:features [[:on-seen
               (fn [section & [init dep]]
                 (let [spec (get sections section)
                       event (or init (:init spec) [:scope/init section])
                       dependency (or dep (:dep spec))]
                   {:event [:on-booted dependency event] :delay-ms 500}))]]}
  [section & [init dep]]
  [:div])

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
   :codex-profile {:module :codex-profile}
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
  {:main layout/landing-layout
   :desktop :something-splitty
   :joen :just-about-me/components
   :av :just-about-company })

(defn section-dependencies
  "Direct sections acquire their content normally; module activation owns its data."
  [_ {:keys [depends content content-deps module supplied-content]}]
  (if module
    []
    (into (vec depends)
          (when-let [keys (seq (or content-deps (when (and content (not supplied-content)) [content])))]
            [{:source :strapi :keys (vec keys)}]))))

(m/defc <get-component> "Get component, and its init event runner, if any."
  {:depends section-dependencies :loading-tag :section :loading-prefab :text}
  [id section-map]
  (let [{:keys [module <comp> <loading> content args dep init supplied-content]} section-map
        route @(r/subscribe [:common/route])
        target (get routes/targets id)
        arguments (cond-> []
                    content (conj (or supplied-content @(r/subscribe [:content/view [content]])))
                    args (conj args))]
    ;; The managed content dependency has resolved before this body mounts.
    ;; Its native section root exists after commit, including a delayed SPA load.
    (r/use-effect
      (fn []
        (when (and target (= target (get-in route [:data :document-target])))
          (r/dispatch [:scroll/component-target target (:path route)])))
      [target (:path route)])
    [:<>
     (when (and init (not module))
       [<run-init> id init dep])
     (if module
       (l/component-vector {:module module
                            :view (or <comp> :view)
                            :load-on :view
                            :<loading> <loading>}
                           arguments)
       (into [<comp>] arguments))]))

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

(defn page-definition [identity]
  (or (page-layout/find-page (page-layout/selected-pages site/config) identity)
      (throw (ex-info "Unknown page layout" {:identity identity}))))

(m/defc <dynamic-section> [section page]
  (let [id (keyword (:component section))
        definition (get sections id {:module id})
        inline (:content section)
        definition' (cond-> definition
                      (= id :interlude) (assoc :args (or (:index section) 0))
                      inline (assoc :supplied-content (assoc inline :identity (:identity section))))]
    (if (= id :page-index)
      [:nav.dynamic-page__index {:aria-label "Page contents"}
       [:h2 "On this page"]
       [:ul (for [item (:sections page) :when (not= "page-index" (:component item))]
              ^{:key (:identity item)}
              [:li [:a {:href (str "#layout-" (:identity page) "-" (:identity item))}
                    (or (:label item) (string/replace (:identity item) "-" " "))]])]]
      [<get-component> id definition'])))

(m/defc <layout> "Compose typed page data with native section boundaries."
  [page :- page-layout/page]
  (let [{:keys [identity sections columns wide outline]} page
        grid? (seq columns)
        column-count @(r/subscribe [:layout/responsive columns 1])]
    [:div.dynamic-page
     {:data-layout identity
      :class [(when grid? "dynamic-page--grid")
              (when wide "dynamic-page--wide")
              (when (= false outline) "dynamic-page--no-outline")]
      :style (into {"--live-columns" column-count}
                   (map (fn [[screen count]] [(str "--columns-" (name screen)) count])) columns)}
     (for [{:keys [identity region span wide outline] :as section} sections]
       ^{:key identity}
       [(if (= region "aside") :aside :div)
        {:id (str "layout-" (:identity page) "-" identity)
         :data-section identity
         :class ["dynamic-page__section"
                 (when wide "dynamic-page__section--wide")
                 (when (= false outline) "dynamic-page__section--no-outline")]
         :style (when grid? {"--section-span" (if wide 12 (or span 1))})}
        [<dynamic-section> section page]])]))

(m/defc <dynamic-page> "Render a published CMS page using the existing section boundaries."
  [identity]
  [<layout> (page-definition identity)])

(m/defpage <auto> "Render the CMS layout selected by the current native route."
  []
  (let [route @(r/subscribe [:common/route])]
    [<dynamic-page> (or (get-in route [:data :layout]) "home")]))
