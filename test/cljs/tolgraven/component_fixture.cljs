(ns tolgraven.component-fixture
  "Interactive local fixture: real blog views and local data, no remote writes."
  (:require [reagent.core :as r]
            [reagent.dom.client :as dom]
            [re-frame.core :as rf]
            [re-frame.db :as rfdb]
            [tolgraven.component :as component]
            [tolgraven.component.data :as data]
            [tolgraven.component.loading :as loading]
            [tolgraven.blog.module]
            [tolgraven.loader :as loader]
            [tolgraven.blog.views :as blog]
            [tolgraven.macros :refer-macros [defc]]))

(defonce *root (atom nil))
(defonce *post (r/atom {:id "fixture" :title "A blog component with its own boundary"
                        :text "This **markdown** is rendered by the migrated blog component."
                        :user "author" :ts 0 :tags "clojure components"}))
(defonce *version (r/atom 1))
(defonce *replies-expanded? (r/atom true))

(defc <counter> {:features [:props]} [spec]
  :let [*clicks (r/atom 0)]
  [:button {:on-click #(swap! *clicks inc)} (str "Sibling counter: " @*clicks)])

(defonce *show-data? (r/atom false))
(defonce *created (r/atom 0))
(def resources [{:source :url :url "/fixtures/component-data.json" :into [:fixture :url]}])
(defc <remote-content> {:depends resources} []
  :let [_ (swap! *created inc)]
  [:section [:h3 (get-in @rfdb/app-db [:fixture :url :title])]
   [:p (get-in @rfdb/app-db [:fixture :url :body])]])

(defonce *cards (r/atom [{:id "one" :title "Remove me, then bring me back"}]))
(defonce *card-unmounts (r/atom 0))
(defc <motion-card> {:features [[:appear "slide-in"] [:exit {:timeout-ms 1500}]]} [card]
  :let [*clicks (r/atom 0)]
  (r/with-let [_ nil]
    [:article.motion-card
     [:h3 (:title card)]
     [:button {:on-click #(swap! *clicks inc)} (str "Card clicks: " @*clicks)]]
    (finally (swap! *card-unmounts inc))))
(defc <motion-list> {:features [:presence]} [cards]
  (into [:section.motion-list {:aria-label "Animated cards"}]
        (map (fn [card] ^{:key (:id card)} [<motion-card> card]) cards)))
(defc <seen-card> {:features [[:seen {:class "slide-in" :once? false}]]} []
  [:article.motion-card [:h3 "Viewport-triggered content"]
   [:p "This card uses its own root for visibility and animation."]])

(defonce *show-persistent? (r/atom true))
(defc <persistent-counter> {:state {:id :fixture-counter :persist true}} []
  :let [*count (<sub :comp [:opts :count] {:initial 0})]
  [:button {:on-click #(>update *count inc)} (str "Persistent count: " @*count)])

(defn <fixture> []
  [:main {:style {:max-width "62rem" :margin "2rem auto" :padding "1rem"}}
   [:h1 "Composable component features"]
   [:section
    [:h2 "Persistent state and loading primitives"]
    [:button {:on-click #(swap! *show-persistent? not)} "Toggle persistent component"]
    [:button {:on-click #(component/dump-state!)} "Save component state"]
    (when @*show-persistent? [<persistent-counter>])
    [:div {:style {:display "flex" :gap "1rem" :align-items "center" :margin "1rem 0"}}
     [loading/<spinner>] [loading/<avatar>] [loading/<span>]]
    [loading/<h1> {:style {:width "20rem"}}]
    [loading/<lines> {:count 3}]]
   [:section
    [:h2 "Animated removal, without wrappers"]
    [:p (str "Completed unmounts: " @*card-unmounts)]
    [:button {:on-click #(reset! *cards [])} "Remove card"]
    [:button {:on-click #(reset! *cards [{:id "one" :title "Remove me, then bring me back"}])} "Restore card"]
    [<motion-list> @*cards]]
   [:p "Local fixture using the migrated blog post, metadata, tags and comments components."]
   [:nav {:aria-label "Fixture controls" :style {:display "flex" :gap "1rem" :flex-wrap "wrap"}}
    [<counter> {:props {:class "fixture-counter"}}]
    [:button {:on-click #(swap! *replies-expanded? not)} "Toggle comment replies"]
    [:button {:on-click #(swap! *post assoc :title #js {:invalid "React child"})}
     "Break blog post"]
    [:button {:on-click #(swap! *post assoc :title "Blog content repaired")}
     "Repair content"]
    [:button {:on-click #(do (swap! *version inc)
                            (swap! *post assoc :text (str "Updated **markdown**, revision " @*version)))}
     "Update content"]]
   [:p "After breaking the post, repair the data and choose Attempt reload. The sibling counter keeps its state."]
   [blog/<blog-post> {:post @*post}]
   [:section
    [:h2 "Data before DOM"]
    [:p (str "Resource: " (name (data/state resources)) "; bodies created: " @*created)]
    [:button {:on-click #(component/prefetch! <remote-content>)} "Preload URL content"]
    [:button {:on-click #(reset! *show-data? true)} "Show data component"]
    (when @*show-data? [<remote-content>])]
   [:div {:style {:height "85vh"}}]
   [<seen-card>]])

(defn ^:export init []
  ;; Fixture-only subscriptions make rendering deterministic without connecting
  ;; to production or changing the real application's event/sub definitions.
  (doseq [event [:user/active-user :user/trusted? :history/back-nav-from-external?
                 :comments/adding? :blog/state]]
    (rf/reg-sub event (fn [_ _] nil)))
  (rf/reg-sub :comments/for-q-flat
    (fn [_ [_ _ parent]]
      (case parent
        nil {:root {:id "root" :user "author" :ts 1 :score 0
                    :title "Fixture comment" :text "A comment with a nested reply."}}
        "root" {:reply {:id "reply" :user "author" :ts 2 :score 0
                        :title "Fixture reply" :text "Reply survives rapid re-expansion."}}
        nil)))
  (rf/reg-sub :comments/thread-expanded? (fn [_ _] @*replies-expanded?))
  (rf/reg-sub :user/user (fn [_ _] {:id "author" :name "Local fixture author"}))
  (rf/reg-sub :href (fn [_ _] "#fixture"))
  (rf/reg-sub :blog/permalink-for-path (fn [_ _] "#fixture-post"))
  (rf/reg-event-fx :run-highlighter! (fn [_ _] {}))
  (rf/reg-sub :scope/inited? (fn [_ _] false))
  (swap! loader/*loads assoc :user
         (js/Promise.resolve {:view {:avatar (fn [_] [:span {:aria-label "Author avatar"} "JT"])}}))
  (when-not @*root
    (reset! *root (dom/create-root (.getElementById js/document "app"))))
  (dom/render @*root [<fixture>]))
