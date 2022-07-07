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

;also the thing about telling browser to expect certain loads
(defn- prefetch [link & [kind]] [:link {:rel "prefetch" :href link :as (or kind "style")}])
; :as document, script, style, font, image

(defn- js [js] [:script (merge {:type "text/javascript" :async true} js)])
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
       [:div.h1-wrapper.center-content
        [:h1.h-intro.h-responsive error-text]])]
    (if-not error-text
      [:div.loading-container ; will be hidden by main-error section z-index
       [:div.loading-wiggle-y
        [:div.loading-wiggle-z
         [:i.loading-spinner.loading-spinner-massive.fa.fa-spinner.fa-spin]]] ]
      [:h1#loading-full-page
       "Error"
       [:i.loading-spinner.fas.fa-bug.fa-spin]])]

   [:footer.footer-sticky ; [:footer>div.footer-content
    [:div.footer-content ;; XXX should adapt to available height, also disappear...
     [:div
      [:h4 "joen.tolgraven@gmail.com"]
      [:h5 "© 2020-2022"]]]]])

; throw in CAPROVER_GIT_COMMIT_SHA somewhere for version
; can pull in w env then fetch from client
(defn- home
  [request & {:keys [loading-content title description pre-pre css-paths js-paths
                     js-raw css-pre js-pre img-pre anti-forgery title-img]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
    [:title title]
    [:meta {:name "og:title" :content title}]
    [:meta {:name "description" :content description}]
    [:meta {:name "og:description" :content description}]
    [:meta {:name "og:image" :content title-img}]
    [:base {:href "/"}]
    
    (for [[path kind] pre-pre]
      (prefetch path kind))
    
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
   [;"https://fonts.googleapis.com/css?family=Open+Sans:300,400,500,600,700,800,900"
    "css/fontawesome.css"
    "css/solid.css"
    "css/brands.min.css"
    "css/opensans.css"
    "https://unpkg.com/leaflet@1.7.1/dist/leaflet.css"])

(def css-pre
  ["css/solid.css"])
(def js-pre
  [#_"js/compiled/out/cljs_base.js"
   #_"js/compiled/app.js"]) ; no work with optimus
(def img-pre
  ["img/foggy-shit-small.jpg"])

(def js-paths
  [;{:src "js/compiled/out/cljs_base.js"}
   ;{:src "js/compiled/app.js"}
   {:src "https://unpkg.com/smoothscroll-polyfill@0.4.4/dist/smoothscroll.min.js"}
   {:src "https://www.googletagmanager.com/gtag/js?id=G-Y8H6RLZX3V"}
   #_{:src "https://modelviewer.dev/node_modules/@google/model-viewer/dist/model-viewer.min.js" :type "module"}])

(def pre-paths
  [["media/fog-3d-small.mp4" "video"]
   #_["https://fonts.googleapis.com/css?family=Open+Sans:300,400,500,600,700,800,900" "font"]
    #_["css/solid.css" "style"]]) ; well it gets bundled anyways

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
                         :pre-pre pre-paths
                         :css-paths css-paths
                         :js-paths js-paths
                         :js-raw google-analytics
                         :css-pre css-pre
                         :js-pre js-pre
                         :img-pre img-pre
                         :title-img "img/logo/tolgraven-logo.png"
                         :anti-forgery (force *anti-forgery-token*))))
      ok
      (content-type "text/html; charset=utf-8")))

(defn render-hiccup
  [page & args]
  (-> (str "<!DOCTYPE html>\n"
           (hiccup/html (apply page args)))
      ok
      (content-type "text/html; charset=utf-8")))


(defn error-page-hiccup
  [request error-details]
  (render-hiccup
   (fn []
     [:html {:lang "en"}
      [:head
       [:meta {:charset "UTF-8"}]
       [:meta {:name "viewport"
               :content "width=device-width, initial-scale=1, shrink-to-fit=no"}]
       [:title "Something bad happened - tolgrAVen"]
       [:meta {:name "description" :content "Error page"}]

       [:link {:href "css/tolgraven/main.min.css" :rel "stylesheet" :type "text/css"}]
       #_(ohtml/link-to-css-bundles error-details ["styles.css"])
       [:script {:type "text/javascript"}
        (str "var csrfToken = \"" *anti-forgery-token* "\";")]] ; this is where everything ends up for prod but cant remember why?



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
   :body    (error-page-hiccup (:request error-details) error-details)})

