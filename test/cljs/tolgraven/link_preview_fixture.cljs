(ns tolgraven.link-preview-fixture
  "Manual browser fixture using real preview components, isolated from Firebase."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [tolgraven.link-preview.module]
            [tolgraven.link-preview.views :as preview]
            [tolgraven.macros :refer-macros [defc]]))

(defonce *root (atom nil))
(defonce *fullscreen? (r/atom false))

(defc <preview-link> {:features [:props :links]} [spec url]
  [:p [:a {:href url} "Open the local preview page"]])

(defn <fixture> []
  (let [url (js/URL. "/preview-target.html" js/window.location.href)
        _ (set! (.-hostname url) (if (= "localhost" (.-hostname url)) "127.0.0.1" "localhost"))
        href (str url)]
    [:<>
     [:div.header-before]
     [:div.fill-side-top [:div {:class (when @*fullscreen? "adjust-for-fullscreen")}]]
     [:header [:h2 "tolgrAVen"]
      [:button {:on-click #(swap! *fullscreen? not)} "Toggle fullscreen framing"]]
     [:main.main-content {:style {:padding "2rem" :min-height "120vh"}}
      [:h1 "Link preview fixture"]
      [:p "Hover or focus the link to preview. Click the preview to navigate, then use Back."]
      [<preview-link> {:links {:id "fixture-link" :text href :trust :untrusted}} href]
      [:p [:a {:href href :target "_blank"} "Open directly in a new tab"]]
      [:p {:style {:margin-top "65vh"}} "Scroll to verify header and footer framing."]]
     [:footer.footer-sticky {:class (when @*fullscreen? "adjust-for-fullscreen")}
      [:div.footer-content [:p "Local browser fixture · no remote services"]]]
     [preview/<link-preview>]]))

(defn ^:export init []
  (when-not @*root
    (reset! *root (rdom/create-root (.getElementById js/document "app"))))
  (rdom/render @*root [<fixture>]))
