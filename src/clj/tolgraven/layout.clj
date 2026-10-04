(ns tolgraven.layout
  (:require
    [clojure.java.io]
    [clojure.data.json :as json]
    [hiccup.core :as hiccup]
    [hiccup.util :as hu]
    [ring.util.http-response :refer [content-type ok]]
    [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
    [ring.util.response]
    [tolgraven.config :refer [env]]
    [tolgraven.content.service :as content]
    [tolgraven.ssr :as ssr]
    [clojure.tools.logging :as log]
    [optimus.link :as olink]
    [optimus.html :as ohtml]))

;also the thing about telling browser to expect certain loads
(defn- preconnect [link] [:link {:rel "preconnect" :href link}])
(defn- prefetch [link & [kind]]
  [:link {:rel "prefetch" :href link :as (or kind "style")}])
; :as document, script, style, font, image

(defn- js [js] [:script (merge {:type "text/javascript" :async true} js)])
(defn- css [href]
  [:link (cond-> {:href href :rel "stylesheet" :type "text/css"}
           (not= href "css/opensans.css") (assoc :media "print" :onload "this.media='all'"))])
(defn- js-preload  [path] [:link {:rel "preload" :as "script" :href path}])
(defn- img-preload [path] [:link {:rel "preload" :as "image" :href path}])
(defn- css-preload [path] [:link {:rel "preload" :as "style" :type "text/css" :href path}])

(defn- img-preload-modern
  "Preload image with modern format support (AVIF/WebP/original).
   Browsers will only fetch the format they support."
  [path]
  (let [base (clojure.string/replace path #"\.(jpg|jpeg|png)$" "")]
    [:<>
     [:link {:rel "preload" :as "image" :href (str base ".avif") :type "image/avif"}]
     [:link {:rel "preload" :as "image" :href (str base ".webp") :type "image/webp"}]
     [:link {:rel "preload" :as "image" :href path}]]))

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
     #_[:img.media.media-as-bg {:src hero-img}]
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
      [:h4 "joen@tolgraven.se"]
      [:h5 (str "© 2020-" (-> (java.util.Date.)
                              .toInstant
                              (.atZone (.toZoneId (java.util.TimeZone/getDefault)))
                              .getYear))]]]]])

; throw in CAPROVER_GIT_COMMIT_SHA somewhere for version
; can pull in w env then fetch from client
(defn- home
  [request & {:keys [loading-content title description link-pre css-paths js-paths
                     js-raw css-pre js-pre img-pre anti-forgery title-img]}]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:meta {:name "color-scheme" :content "light dark"}]
    [:title (hu/escape-html (or title ""))]
    [:meta {:name "og:title" :content title}]             ; for link previews
    [:meta {:name "description" :content description}]
    [:meta {:name "og:description" :content description}]
    [:meta {:name "og:image" :content title-img}]         ; ideally would get overridden on like, blog-post with cover img...
    [:meta {:name "theme-color" :content "#1A1C1C"
            :media "(prefers-color-scheme: dark)"}]
    [:meta {:name "theme-color" :content "#edc"
            :media "(prefers-color-scheme: light)"}]    ; for mobile safari status bar
    [:meta {:name "mobile-web-app-capable" :content "yes"}]
    #_[:meta {:name "apple-mobile-web-app-status-bar-style"
            :content "black-translucent"}] ; ought to be theme dependent tho
    [:base {:href "/"}]
    [:link {:rel "icon" :type "image/png" :sizes "32x32" :href "/favicon-32x32.png"}]
    [:link {:rel "icon" :type "image/png" :sizes "16x16" :href "/favicon-16x16.png"}]
    [:link {:rel "apple-touch-icon" :sizes "120x120" :href "/apple-touch-icon.png"}]
    [:link {:rel "manifest" :href "/site.webmanifest"}]

    (for [link link-pre]
      (preconnect link))

    (for [path img-pre]
      (img-preload-modern path))

    (for [path css-pre]
      (css-preload path))

    [:link {:rel "preload" :as "font" :type "font/woff2" :crossorigin "anonymous"
            :href "/webfonts/OpenSans-v29-latin.woff2"}]
    ;; The layout must be styled before first paint, including on a cold/private visit.
    (for [path (if (:dev env)
                 ["css/tolgraven/main.min.css"]
                 (olink/bundle-paths request ["styles.css"]))]
      [:link {:href path :rel "stylesheet" :type "text/css"}])
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

    [:div#app (cond-> {} (:ssr request) (assoc :data-hydrate "true")
                         (:restore? request) (assoc :data-restore "true")) loading-content]
    ;; A separate React root can report bootstrap failures without replacing
    ;; server HTML that has not yet been hydrated.
    [:div#page-init-status]
    (when-let [snapshot (get-in request [:ssr :snapshot])]
      [:script#ssr-bootstrap {:type "application/json"} (content/hydration-json snapshot)])
    (when-let [bundle (:site-content request)]
      [:script#site-content-bootstrap {:type "application/json"} (content/hydration-json bundle)])
    (ohtml/link-to-js-bundles request ["main.js"]) ]])

(defn render-hiccup
  [page & args]
  (-> (str "<!DOCTYPE html>\n"
           (hiccup/html (apply page args)))
      ok
      (content-type "text/html; charset=utf-8")))

(def render-hiccup-memo) ; well no because of anti forgery token, requests differing etc

(defn returning-page? [request]
  (try
    (when-let [value (some-> (get-in request [:cookies "tolgraven-return" :value])
                             (java.net.URLDecoder/decode "UTF-8"))]
      (or (= (:uri request) value) ; compatibility with the original single path
          (let [paths (json/read-str value)]
            (and (vector? paths) (<= (count paths) 16)
                 (boolean (some #{(:uri request)} paths))))))
    (catch Exception _ false)))

(defn render-home
  [request]
  (let [script-paths (olink/bundle-paths request ["main.js"])
        returning? (returning-page? request)
        ssr (when (and (not returning?) (ssr/enabled?) (ssr/route (:uri request)))
              (try (ssr/page! (:uri request) (:query-params request))
                   (catch Exception error
                     (log/error "Page SSR unavailable; returning a retryable public error")
                     {:error? true
                      :status (if (= 404 (:status (ex-data error))) 404 503)})))
        request (cond-> request
                  returning? (assoc :restore? true)
                  (:snapshot ssr) (assoc :ssr ssr
                                        :site-content {:version 1 :deferred? true
                                                       :content (get-in ssr [:snapshot :content])}))]
  (cond-> (render-hiccup
   home
   (if (or (:ssr request) returning?) request (if-let [mode (System/getenv "CONTENT_BOOTSTRAP_MODE")]
     (if (#{"route" "full"} mode)
       (let [route (keyword (or (second (clojure.string/split (:uri request) #"/")) "home"))
             bundle (if (= mode "route") (assoc (content/for-route! route) :deferred? true) (content/bundle!))]
         (assoc request :site-content bundle))
       request)
     request))
   :loading-content (when-not returning? (or (:html ssr)
                        (when (:error? ssr)
                          [:main.main-content [:p {:role "alert"} "Page content could not load. Please retry."]
                           [:a {:href (:uri request)} "Retry"]])
                        (basic-skeleton "tolgrAVen" ["audio" "visual"] "img/foggy-shit-small.jpg")))
   :title (or (when (= 1 (count (get-in ssr [:snapshot :posts])))
                (:title (first (get-in ssr [:snapshot :posts]))))
              (get-in ssr [:snapshot :content :document :title]) "tolgrAVen audiovisual")
   :description (or (get-in ssr [:snapshot :content :document :description])
                    "tolgrAVen audiovisual by Joen Tolgraven")
   :pre-pre [["media/fog-3d-small.mp4" "video"]]
   :css-paths [
               "css/fontawesome.css"
               "css/solid.css"
               "css/brands.min.css"
               "css/opensans.css"]
   :js-paths (concat [{:src "https://unpkg.com/smoothscroll-polyfill@0.4.4/dist/smoothscroll.min.js"}]
                     [{:src "/vendor/supabase.js" :async false}]
                     (when-not (:dev env)
                       [{:src "https://www.googletagmanager.com/gtag/js?id=G-Y8H6RLZX3V"}]))
   :js-raw (when-not (:dev env)
             ["window.dataLayer = window.dataLayer || [];
               function gtag(){dataLayer.push(arguments);}
               gtag('js', new Date());

               gtag('config', 'G-Y8H6RLZX3V');"])
   :css-pre ["css/solid.css"]
   :js-pre script-paths
   :img-pre [#_"img/logo/tolgraven-logo.png"]  ; Preload logo for instant display
   :link-pre (when-not (:dev env)
               ["https://fonts.gstatic.com"
                "https://www.googletagmanager.com"
                "https://region1.google-analytics.com"])
   :title-img "img/logo/tolgraven-logo.png"
   :anti-forgery (force *anti-forgery-token*))
    (seq script-paths) (assoc-in [:headers "Link"]
                                (clojure.string/join ", "
                                  (map #(str "<" % ">; rel=preload; as=script") script-paths)))
    (:error? ssr) (assoc :status (:status ssr))
    (get-in ssr [:snapshot :missing?]) (assoc :status 404)
    (or ssr returning?) (assoc-in [:headers "Cache-Control"] "no-store"))))

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

       (when-not (:dev env)
         [:link {:href "/css/tolgraven/main.min.css" :rel "stylesheet" :type "text/css"}])
       (ohtml/link-to-css-bundles request ["styles.css"])
       [:script {:type "text/javascript"}
        (str "var csrfToken = \"" (force *anti-forgery-token*) "\";")]] ; this is where everything ends up for prod but cant remember why?

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

   :body    (:body (error-page-hiccup (:request error-details) error-details))})
