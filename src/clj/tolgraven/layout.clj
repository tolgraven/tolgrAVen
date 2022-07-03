(ns tolgraven.layout
  (:require
    [clojure.java.io]
    [hiccup.core :as hiccup]
    [markdown.core :refer [md-to-html-string]]
    [ring.util.http-response :refer [content-type ok]]
    [ring.util.anti-forgery :refer [anti-forgery-field]]
    [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
    [ring.util.response]
    [tolgraven.config :refer [env]]
    [optimus.link :as olink]
    [optimus.html :as ohtml]))


(defn- js [js] [:script (merge js {:type "text/javascript" :async true})])
(defn- css [href] [:link {:href href :rel "stylesheet" :type "text/css" :media "print" :onload "this.media='all'"}])
(defn- js-preload  [path] [:link {:rel "preload" :as "script" :href path}])
(defn- img-preload [path] [:link {:rel "preload" :as "image" :href path}])
(defn- css-preload [path] [:link {:rel "preload" :as "style" :type "text/css" :href path}])

(defn- loading-spinner
  [text]
  [:h1#loading-full-page
    text
    [:i.loading-spinner.fa.fa-spinner.fa-spin]])

(defn- basic-skeleton "Skeleton for main page layout. Would be nice if faded in yo. Css animation?"
  [header-text subtext hero-img & [error-text main-class]]
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

   [:main#main.main-loading.main-content
    {:class main-class}
    [:section#intro
     [:img.media.media-as-bg {:src hero-img}]
     (when error-text
       [:h1.h-intro error-text])]
    (when-not error-text ; just loading
      [:div.loading-container
        [:div.loading-wiggle-y
         [:div.loading-wiggle-z
          [:i.loading-spinner.loading-spinner-massive.fa.fa-spinner.fa-spin]]] ])]

   [:footer.footer-sticky ; [:footer>div.footer-content
    [:div.footer-content ;; XXX should adapt to available height, also disappear...
     [:div
      [:h4 "joen.tolgraven@gmail.com"]
      [:h5 "© 2020-2022"]]]]])

(defn- home
  [request & {:keys [loading-content title description css-paths js-paths js-raw css-pre js-pre img-pre anti-forgery]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:title title]
    [:meta {:name "description" :content description}]
    [:base {:href "/"}]
    
    (for [path img-pre]
      (img-preload path))

    (for [path css-pre]
      (css-preload path))
    (ohtml/link-to-css-bundles request ["styles.css"]) ; this is where everything ends up for prod but cant remember why?
    (for [href css-paths]
      (css href))
    
    (when anti-forgery
      [:script {:type "text/javascript"}
       (str "var csrfToken = \"" anti-forgery "\";")])
    (for [path js-pre]
      (js-preload path))
    (for [path js-paths]
      (js path))
    (for [script js-raw]
      [:script {:type "text/javascript"} script])]
    
   [:body {:class "container themable framing-shadow sticky-footer-container"}
    
    [:div#app loading-content]
    
    (ohtml/link-to-js-bundles request ["app.js"]) ]])


(def css-paths ; should come from config?
   ["https://fonts.googleapis.com/css?family=Open+Sans:300,400,500,600,700,800,900"
    "css/fontawesome.css"
    "css/solid.css"
    "css/brands.min.css"
    "https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"])

(def css-pre
  ["css/solid.css"])
(def js-pre
  ["js/compiled/app.js"]) ; no work with optimus
(def img-pre
  ["img/foggy-shit-small.jpg"])

(def js-paths
  [{:src "https://unpkg.com/smoothscroll-polyfill@0.4.4/dist/smoothscroll.min.js"}
   {:src "https://www.googletagmanager.com/gtag/js?id=G-Y8H6RLZX3V"}])

(def google-analytics
  (when-not (:dev env)
    ["window.dataLayer = window.dataLayer || [];
      function gtag(){dataLayer.push(arguments);}
      gtag('js', new Date());

      gtag('config', 'G-Y8H6RLZX3V');"]))

(defn render-home
  [request]
  (-> (str "<!DOCTYPE html>\n"
           (hiccup/html (home
                         request
                         :loading-content (basic-skeleton "tolgrAVen" ["audio" "visual"]
                                                          "img/foggy-shit-small.jpg")
                         :title "tolgrAVen audiovisual"
                         :description "tolgrAVen audiovisual by Joen Tolgraven"
                         :css-paths css-paths
                         :js-paths js-paths
                         :js-raw google-analytics
                         :css-pre css-pre
                         ; :js-pre js-pre
                         :img-pre img-pre
                         :anti-forgery *anti-forgery-token*)))
      ok
      (content-type "text/html; charset=utf-8")))

(defn render-hiccup
  [page & args]
  (-> (str "<!DOCTYPE html>\n"
           (hiccup/html (apply page args)))
      ok
      (content-type "text/html; charset=utf-8")))


(defn error-page-hiccup
  [error-details]
  (str
   "<!DOCTYPE html>\n"
   (hiccup/html

    [:html {:lang "en"}
     [:head
      [:meta {:charset "UTF-8"}]
      [:meta {:name "viewport"
              :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
      [:title "Something bad happened - tolgrAVen"]
      [:meta {:name "description" :content "Error page"}]

      [:link {:href "css/tolgraven/main.min.css" :rel "stylesheet" :type "text/css"}]
      #_(ohtml/link-to-css-bundles error-details ["styles.css"])] ; this is where everything ends up for prod but cant remember why?

     [:script {:type "text/javascript"}
      (str "var csrfToken = \"" *anti-forgery-token* "\";")]

     [:body {:class "container themable framing-shadow sticky-footer-container"}
      (basic-skeleton "tolgrAVen" ["error" (str (:status error-details))]
                      "img/foggy-shit-small.jpg"
                      (:title error-details)
                      "main-error")]])))

(defn error-page ; reckon bail on this and make in hiccup then can nuke parser.
  "error-details should be a map containing the following keys:
   :status - error status
   :title - error title (optional)
   :message - detailed error message (optional)

   returns a response map with the error page as the body and the status specified by the status key"
  [error-details]
  {:status  (:status error-details)
   :title   {:title error-details}
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body    (error-page-hiccup error-details)})

