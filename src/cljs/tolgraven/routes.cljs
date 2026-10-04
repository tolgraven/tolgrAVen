(ns tolgraven.routes
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.ssr.client :as ssr] [tolgraven.react :as rf]
    [reitit.frontend :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [reitit.dev.pretty :as rpretty]
    [tolgraven.blog.pages :as blog]
    [tolgraven.cv.pages :as cv]
    [tolgraven.docs.pages :as docs]
    [tolgraven.loader :as l]
    [tolgraven.component.data :as data]
    [tolgraven.components.error :as error-view]
    [tolgraven.main.module :as main]
    [tolgraven.component.restore :as restore]
    [tolgraven.ui :as ui]
    [tolgraven.main.pages :as home]
    [tolgraven.views.auto :as auto]
    [tolgraven.views.not-found :as a404]))

(defc <log-page> []
  [ui/<with-heading> [:common :banner-heading]
   [ui/<log> (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(def router
  (reitit/router
    (into ["" {:controllers [{:parameters {:query [:userBox :settingsBox]} ; ok so this how done. but surely will get unwieldy af?
                     :start (fn [{:keys [query]}]    ; and how get to update url with changes...
                              (case (:userBox query) ; was thinking "have :user/open-ui passing true/false and no case but would be spammy"
                               "true" (rf/dispatch [:user/open-ui])
                               ("false" nil) (rf/dispatch [:user/close-ui]))
                              (case (:settingsBox query)
                                "true" (do (rf/dispatch [:state [:settings :panel-open] true])
                                           (rf/dispatch [:scroll/to-top-and-arm-restore]))
                                ("false" nil) (rf/dispatch [:state [:settings :panel-open] false]))) ; well this being on start it wouldn't be open anyways
                     :stop (fn [{:keys [query]}]    ; why is this being run without leaving page?
                             )}]}]
          (concat (mapv #(update % 1 assoc :module nil :view #'auto/<auto>) home/spec)
                  [(into [""] cv/spec)
                   (into [""] docs/spec)
                   (into [""] blog/spec)
                   ["/log" {:name :log :view #'<log-page>}]
                   ["/test"
      ["" {:name :test
           :module :test
           :page :page}]
      ["/:tab"
       {:name :test-tab
        :module :test
        :page :page
       :controllers [{:parameters {:path [:tab]} ; seems like a nice middle ground of using routing and urls but not fully integrating (needing these views available/known here for example). So, keep doing sub tabs like this?
                      :start (fn [{:keys [path]}]
                               (rf/dispatch [:state [:experiments] (keyword (:tab path))])
                               (rf/dispatch [:exception [:experiments] nil]))}]}]]
                   ["/client-oauth" ; for client OAuth callbacks
                    {:view #'a404/<not-found-page>}
                    ["" {:name :client-oauth
                         #_:view #_#'successful-oauth-page}]
                    #_["/:service" ; nope, considering non-universal naming unless can coerce keys to universal api/secret/etc...
                       {:name :client-api-service
                        :view #'test-page
                        :controllers [{:parameters {:path [:service]}
                                       :start (fn [{:keys [path]}]
                                                )}]}]
                    ["/twitter"
                     {:name :client-oauth-twitter
                      :controllers
                      [{:parameters {:query [:oauth_token :oauth_token_secret
                                             :oauth_callback_confirmed]}
                        :start (fn [{:keys [query]}]
                                 (if (:oauth_callback_confirmed query)
                                   (rf/dispatch [:oauth/store-token-twitter
                                                 (:oauth_token query)
                                                 (:oauth_token_secret query)])
                                   (rf/dispatch [:diag/new :error "Twitter auth"
                                                 "Error authenticating"])))}]}]]
                   ["/not-found" {:name :not-found :view #'a404/<not-found-page>}]]))
    {:exception rpretty/exception}))

;; A late module response must never navigate back over a newer URL.
(defonce *navigation (atom 0))

(defn navigate!
  "Resolve a route with injectable loading and dispatch for isolated regression tests."
  [*navigation dispatch! load! match]
  (when-let [path (:path match)] (restore/navigate! path))
  (let [navigation (swap! *navigation inc)
        {:keys [module page view name]} (:data match)
        retry! (fn []
                 (when (= :landing (:kind (:data match)))
                   (data/invalidate! (set (:depends main/spec))))
                 (navigate! *navigation dispatch! load! match))
        fail! (fn [error]
                (when (= navigation @*navigation)
                  (dispatch! [:diag/new :error "Page failed to load" (str name)])
                  (dispatch! [:state [:error-page]
                              (fn [] [error-view/<failure> "page" (str name)
                                      {:title "This page could not be loaded"
                                       :message "Check your connection and try loading this page again."
                                       :error error}
                                      retry!])])))
        navigate! (fn [component]
                    (when (= navigation @*navigation)
                      (if component
                        (dispatch! [:common/navigate
                                      (assoc-in match [:data :view] component)])
                        (dispatch! [:state [:error-page] a404/<not-found-page>]))))]
    (cond
      (nil? match)
      (do (dispatch! [:state [:error-page] a404/<not-found-page>])
          (dispatch! [:diag/new :error "404" "Not found"]))

      view (if (= :landing (:kind (:data match)))
             (-> (data/ensure-all! (:depends main/spec))
                 (.then #(navigate! view))
                 (.catch fail!))
             (navigate! view))

      module
      (do
        ;; Hydrated content is already visible while the module initializes.
        ;; Cached modules likewise need no intermediate page spinner.
        (when-not (or (and (:path match) (= (:path match) (:path @ssr/*snapshot)))
                      (and (identical? load! l/load!) (l/ready-spec module page nil)))
          (dispatch! [:loading/on :page navigation]))
        (-> (load! {:module module :view page :route match})
            (.then #(navigate! (get-in % [:view page])))
            (.catch fail!)
            (.finally #(dispatch! [:loading/off :page navigation]))))

      :else (navigate! nil))))

(defn on-nav [match _history]
  (when-let [path (:path match)] (ssr/leave! path))
  (navigate! *navigation rf/dispatch l/load! match))

(defn ignore-anchor-click? [router event element ^goog.Uri uri]
  ;; Only intercepted internal links may update the pending fragment.
  (let [ignore? (rfh/ignore-anchor-click? router event element uri)]
    (when ignore?
      (rf/dispatch [:state [:fragment] (.getFragment uri)]))
    ignore?))

(defn start! []
  (rfe/start! router on-nav {:use-fragment false
                           :ignore-anchor-click? ignore-anchor-click?}))


(defn external-http-url?
  "True when href resolves to an HTTP(S) URL on a different origin."
  [href base-url]
  (try
    (let [url (js/URL. href base-url)
          base (js/URL. base-url)]
      (and (contains? #{"http:" "https:"} (.-protocol url))
           (not= (.-origin url) (.-origin base))))
    (catch :default _
      false)))
