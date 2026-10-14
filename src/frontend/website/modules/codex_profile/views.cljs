(ns website.modules.codex-profile.views
  (:require [website.macros :refer-macros [defc]]
            [website.react :as r]
            [website.components.activity :as activity]))

(defc <profile> []
  (let [{:keys [display-name profile-url bio days]} @(r/subscribe [:content [:codex-profile]])]
    [:section.codex-profile.covering-2
     [:h2 [:a {:href profile-url} (str display-name " on ChatGPT / Codex")]]
     (when (seq bio) [:p bio])
     (if (some? days)
       [activity/<calendar> days]
       [:p.codex-profile__unavailable "Activity is not publicly available yet."])
     [:a {:href profile-url} "View public profile ↗"]]))
