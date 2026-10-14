(ns website.editor-visual
  "Deterministic browser component fixture for before/after screenshot review."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.events]
            [website.subs]
            [website.db :as db]
            [website.navigation.routes :as routes]
            [reitit.core :as reitit]
            [website.component.restore :as restore]
            [website.components.shell :as shell]
            [website.components.heading :as heading]
            [website.components.image :as image]
            [website.components.media :as media]
            [website.modules.home.sections :as home]
            [website.modules.cv.views :as cv]
            [website.modules.user.views :as user]
            [website.content.contract :as content]))

(def profile {:id "visual-admin", :name "Visual reviewer", :email "review@example.invalid"
              :avatar nil, :bg-color nil, :comment-count 0, :karma 0, :roles ["admins"]})
(r/reg-event-db :editor-visual/install
  (fn [_ [_ seed edit?]]
    (-> db/data
        (assoc :content (content/normalize-content seed)
               :common/route (reitit/match-by-path routes/router "/"))
        (assoc-in [:state :gallery :loaded] true)
        (assoc-in [:state :active-user] (when edit? profile))
        (assoc-in [:state :content-editor] {:owner "visual-admin", :enabled? edit?, :fields {}}))))

(defn section [key enhanced?]
  @(r/subscribe [(if enhanced? :content/view :content) [key]]))

(defn <fixture> [component enhanced?]
  (let [content' #(section % enhanced?)]
    [:main.main-content.perspective-top {:id "main"}
     (case component
       "intro" [home/<intro> (content' :intro)]
       "services" [home/<services> (content' :services)]
       "story" [home/<story> (content' :story)]
       "moneyshot" [media/<moneyshot> (content' :moneyshot)]
       "interlude" [media/<interlude> (content' :interlude) 0]
       "gallery" [media/<gallery> (content' :gallery)]
       "header" [shell/<header> (content' :header)]
       "footer" [shell/<footer-full> (content' :footer)]
       "sticky-footer" [shell/<footer> (content' :footer)]
       "post-footer" [shell/<post-footer> (content' :post-footer)]
       "cv" [cv/<cv>]
       "blog-heading" [heading/<banner> (:heading (content' :blog))]
       "docs-heading" [heading/<banner> (:heading (content' :docs))]
       "image" [image/<picture> (assoc (get (content' :gallery) 0) :class "media" :style {:width "30rem", :max-width "100%"})]
       "admin" [user/<admin> profile]
       [:p "Unknown component"])]))

(defn ^:export init! [enhanced?]
  (let [params (js/URLSearchParams. (.-search js/location))
        component (or (.get params "component") "intro")
        edit? (= "true" (.get params "edit"))]
    (-> (js/fetch "/fixtures/editor-seed.json")
        (.then #(.json %))
        (.then (fn [seed]
                 (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) edit?])
                 (restore/begin! {:hydrate? false, :back? true})
                 (r/render (r/create-root (.getElementById js/document "app")) [<fixture> component enhanced?]))))))
