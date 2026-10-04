(ns tolgraven.ssr.views
  (:require [clojure.string :as string]
            [tolgraven.blog.ssr-view :as blog]
            [tolgraven.ssr.landing :as landing]))

(defn <header> [{:keys [text menu]}]
  [:<>
   [:input#nav-menu-open.burger-check {:type "checkbox" :default-checked false}]
   [:header
    [:div.cover.cover-clip]
    [:div.header-logo
     [:a {:href "/"} [:h1 (first text)]]
     [:div.header-logo-text (for [line (second text)] ^{:key line} [:p line])]]
    [:menu
     [:nav
      (for [group [:work :personal]]
        ^{:key group}
        [:div.nav-section
         [:ul.nav-links (when (= group :personal) {:style {:position :absolute :right 0 :top 0}})
          (for [[label href] (get menu group)]
            ^{:key label} [:li [:a {:href (if (string/starts-with? href "/") href (str "/" href))
                                   :id (str "menu-link-" (string/lower-case label))}
                               (string/upper-case label)]])]])]
     [:div.menu-toggles {:style {:position "absolute" :right 0 :bottom 0}}]]
    [:div.header-icons
     [:a {:href "/blog"} [:button.blog-link-btn.noborder.nomargin {:title "My blog"} [:i.fa.fa-pen-fancy]]]
     [:a {:href "?settingsBox=true"} [:button.settings-btn.noborder.nomargin {:title "Settings"} [:i.fa.fa-cog]]]
     [:label.burger {:for "nav-menu-open"}]]]
   [:div.fill-side-top [:div.hide]]
   [:div.fill-above-line-header]
   [:div.line.line-header]])

(defn <footer-column> [{:keys [id email text links logo]}]
  [:div.footer-column {:id (str "footer-" id)}
   (when logo [landing/<image> logo "img-icon" false])
   [:div (when email [:a {:href (str "mailto:" email)} email])
    (for [line text] ^{:key line} [:h5 line])]
   (when links
     [:div.footer-icons
      (for [{:keys [name href icon]} links]
        ^{:key name} [:a {:href href :aria-label name}
                      [:i.fab {:class (str "fa-" icon)}]])])])

(defn <footer> [content]
  [:footer#footer-sticky.footer-sticky
   [:div.footer-content
    (for [column content] ^{:key (:id column)} [<footer-column> column])]])

(defn <page>
  "Stable page content surrounded by optional browser enhancements. The first
   client render passes no enhancements and exactly matches server markup."
  [{:keys [content kind] :as snapshot} & [{:keys [comments header footer chrome notices] :as actions}]]
  [:<>
   (if header [header (:header content)] [<header> (:header content)])
   (when notices [notices])
   [:main#main.main-content.perspective-top {:data-ssr true}
    (if (= "landing" (name (or kind :blog)))
      [landing/<page> content (assoc actions :email (some :email (:footer content)))]
      [blog/<body> snapshot comments])]
   (if footer [footer content] [<footer> (:footer content)])
   (when chrome [chrome])])
