(ns tolgraven.routes
  (:require [re-frame.core :as rf]
            [reitit.frontend :as reitit]
            [reitit.frontend.history :as rfh]
            [reitit.frontend.easy :as rfe]
            [reitit.dev.pretty :as rpretty]
            [tolgraven.blog.routes :as blog]
            [tolgraven.cv.routes :as cv]
            [tolgraven.docs.routes :as docs]
            [tolgraven.loader :as l]
            [tolgraven.ui :as ui]
            [tolgraven.views.home :as home]
            [tolgraven.views.not-found :as a404]))

(defn log-page []
  [ui/with-heading [:common :banner-heading]
   [ui/log (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(def router
  (reitit/router
    (into ["/" {:controllers [{:parameters {:query [:userBox :settingsBox]} ; ok so this how done. but surely will get unwieldy af?
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
          (concat home/routes
                  [cv/routes docs/routes blog/routes
                   ["log" {:name :log :view #'log-page}]
                   ["test"
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
                   ["client-oauth"
                    {:view #'a404/not-found-page}
                    ["" {:name :client-oauth}]
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
                   ["not-found" {:name :not-found :view #'a404/not-found-page}]]))
    {:exception rpretty/exception}))

;; A late module response must never navigate back over a newer URL.
(defonce *navigation (atom 0))

(defn navigate!
  "Resolve a route with injectable loading and dispatch for isolated regression tests."
  [*navigation dispatch! load! match]
  (let [navigation (swap! *navigation inc)
        {:keys [module page view name]} (:data match)
        navigate! (fn [component]
                    (when (= navigation @*navigation)
                      (if component
                        (dispatch! [:common/navigate
                                      (assoc-in match [:data :view] component)])
                        (dispatch! [:state [:error-page] a404/not-found-page]))))]
    (cond
      (nil? match)
      (do (dispatch! [:state [:error-page] a404/not-found-page])
          (dispatch! [:diag/new :error "404" "Not found"]))

      view (navigate! view)

      module
      (do
        (dispatch! [:loading/on :page navigation])
        (-> (load! {:module module})
            (.then #(navigate! (get-in % [:view page])))
            (.catch (fn [error]
                      (when (= navigation @*navigation)
                        (dispatch! [:diag/new :error "Page failed to load" (str name)])
                        (navigate! nil))
                      (js/console.error "Module load failed" module error)))
            (.finally #(dispatch! [:loading/off :page navigation]))))

      :else (navigate! nil))))

(defn on-nav [match _history]
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
