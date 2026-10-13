(ns tolgraven.modules.codex-profile.views
  (:require [tolgraven.macros :refer-macros [defc]]
            [tolgraven.react :as rf]
            [tolgraven.components.activity :as activity]))

(defc <profile> []
  (let [{:keys [display-name profile-url bio days]} @(rf/subscribe [:content [:codex-profile]])]
    [:section.codex-profile.covering-2
     [:h2 [:a {:href profile-url} (str display-name " on ChatGPT / Codex")]]
     (when (seq bio) [:p bio])
     (if (some? days)
       [activity/<calendar> days]
       [:p.codex-profile__unavailable "Activity is not publicly available yet."])
     [:a {:href profile-url} "View public profile ↗"]]))
