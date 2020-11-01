(ns tolgraven.layout
  (:require
    [clojure.java.io]
    [selmer.parser :as parser]
    [selmer.filters :as filters]
    [hiccup.core :as hiccup]
    [markdown.core :refer [md-to-html-string]]
    [ring.util.http-response :refer [content-type ok]]
    [ring.util.anti-forgery :refer [anti-forgery-field]]
    [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
    [ring.util.response]))

(parser/set-resource-path!  (clojure.java.io/resource "html"))
(parser/add-tag! :csrf-field (fn [_ _] (anti-forgery-field)))
(filters/add-filter! :markdown (fn [content] [:safe (md-to-html-string content)]))

; was thimking before give server-side render a go can at least replicate basic
; structure of it. but first just make general hiccup
(defn- js [js] [:script (merge js {:type "text/javascript"})])
(defn- css [href] [:link {:href href :rel "stylesheet" :type "text/css"}])

(defn- loading-spinner
  [text]
  [:h1#loading-full-page
    text
    [:i.loading-spinner.fa.fa-spinner.fa-spin]])

(defn- basic-skeleton "Skeleton for main page layout. Would be nice if faded in yo. Css animation?"
  [header-text subtext]
  [:div
   [:header
    [:div.header-logo
     [:a {:href "#"} [:h1 header-text]]
     [:div.header-logo-text
      (for [line subtext] (:p line))]]
    [:menu [:nav]]
    [:i.fa.fa-spinner.fa-spin]
    [:i.user-btn {:class "fa fa-user"}]
    [:label.burger]]
   [:div.line.line-header]

   [:main.main-loading
    [:div.loading-container
     [:div.loading-wiggle-y
      [:div.loading-wiggle-z
       [:i.loading-spinner.loading-spinner-massive.fa.fa-spinner.fa-spin]]] ]]

   [:footer.footer-sticky ; [:footer>div.footer-content
    [:div.line.line-footer] ;cant this be outside main ugh
    [:div.footer-content ;; XXX should adapt to available height, also disappear...
     [:div
      [:h4 "joen.tolgraven@gmail.com"]
      [:h5 "c 2020"]]]]])

(defn- home
  [& {:keys [loading-content title description css-paths js-paths anti-forgery]
      :or {title "tolgrAVen"
           description "A website by Joen Tolgraven"
           css-paths ["css/main.min.css"]
           js-paths ["js/compiled/app.js"]}}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:title title]
    [:meta {:name "description" :content description}]
    [:base {:href "/"}]

    (for [href css-paths]
      (css href))]
 
   [:body {:class "container themable framing-shadow sticky-footer-container"}
    
    [:div#app loading-content]

    (when anti-forgery
      [:script {:type "text/javascript"}
       (str "var csrfToken = \"" anti-forgery "\";")])
    (for [path js-paths]
      (js path))]])

(def css-paths ; should come from config?
   ["css/main.min.css"
    "css/fontawesome.css"
    "css/brands.min.css"
    "css/solid.css"
    "https://fonts.googleapis.com/css?family=Open+Sans:300,400,500,600,700,800,900"])

(def js-paths
  [{:src "js/compiled/app.js"}])

(defn render-hiccup
  [request]
  (-> (str "<!DOCTYPE html>\n"
           (hiccup/html (home
                         ; :loading-content (loading-spinner "Stand by for tolgrAVen...")
                         :loading-content (basic-skeleton "tolgrAVen" ["audio" "visual"])
                         :title "tolgrAVen"
                         :css-paths css-paths
                         :js-paths js-paths
                         :anti-forgery *anti-forgery-token*)))
      ok
      (content-type "text/html; charset=utf-8")))


(defn render
  "renders the HTML template located relative to resources/html"
  [request template & [params]]
  (content-type
    (ok
      (parser/render-file
        template
        (assoc params
          :page template
          :csrf-token *anti-forgery-token*)))
    "text/html; charset=utf-8"))

(defn error-page ; reckon bail on this and make in hiccup then can nuke parser.
  "error-details should be a map containing the following keys:
   :status - error status
   :title - error title (optional)
   :message - detailed error message (optional)

   returns a response map with the error page as the body and the status specified by the status key"
  [error-details]
  {:status  (:status error-details)
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (parser/render-file "error.html" error-details)})
