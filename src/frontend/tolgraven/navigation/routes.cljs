(ns tolgraven.navigation.routes
  (:require
    [tolgraven.component.registry]
    [tolgraven.validation :as validation]
    [tolgraven.validation.runtime :as validation-runtime]
    [tolgraven.schema.http :as schemas]
    [tolgraven.schema.page-coercion :as page-coercion]
    [reitit.coercion :as coercion]
    [tolgraven.service-status :as status]
    [tolgraven.navigation.transition]
    [tolgraven.macros :refer-macros [defc defpage]]
    [tolgraven.ssr.client :as ssr] [tolgraven.react :as rf]
    [reitit.frontend :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [tolgraven.diagnostics.routes :as diagnostics]
    [tolgraven.modules.blog.pages :as blog]
    [tolgraven.modules.cv.pages :as cv]
    [tolgraven.modules.docs.pages :as docs]
    [tolgraven.loader :as l]
    [tolgraven.component.data :as data]
    [tolgraven.content.contract :as content]
    [tolgraven.components.error :as error-view]
    [tolgraven.components.page-shell :as shell]
    [tolgraven.component.restore :as restore]
    [tolgraven.components.ui :as ui]
    [tolgraven.modules.home.pages :as home]
    [tolgraven.components.not-found :as a404]))

(defpage <log-page>
  {:depends [{:source :strapi :keys [:common]}]}
  []
  [ui/<with-heading> [:common :banner-heading]
   [ui/<log> (rf/subscribe [:option [:log]])
    (rf/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(def router
  (reitit/router
    (into ["" {:coercion schemas/coercion :parameters {:query schemas/page-query}
               :controllers [{:parameters {:query [:userBox :settingsBox]} ; ok so this how done. but surely will get unwieldy af?
                     :start (fn [{:keys [query]}]    ; and how get to update url with changes...
                              (case (:userBox query) ; was thinking "have :user/open-ui passing true/false and no case but would be spammy"
                               true (rf/dispatch [:user/open-ui])
                               (false nil) (rf/dispatch [:user/close-ui]))
                              (case (:settingsBox query)
                                true (do (rf/dispatch [:state [:settings :panel-open] true])
                                           (rf/dispatch [:scroll/to-top-and-arm-restore]))
                                (false nil) (rf/dispatch [:state [:settings :panel-open] false]))) ; well this being on start it wouldn't be open anyways
                     :stop (fn [{:keys [query]}]    ; why is this being run without leaving page?
                             )}]}]
          (concat home/spec
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
    (merge diagnostics/options {:validate validation-runtime/validate-routes!})))

;; A late module response must never navigate back over a newer URL.
(defonce *navigation (atom 0))

(defn landing-dependencies [match]
  [{:source :strapi :keys (content/keys-for-route (get-in match [:data :name]))}])

(defn navigate!
  "Resolve a route with injectable loading and dispatch for isolated regression tests."
  [*navigation dispatch! load! match]
  (when-let [path (:path match)] (restore/navigate! path))
  (let [navigation (swap! *navigation inc)
        {:keys [module page view name]} (:data match)
        retry! (fn []
                 (when (= :landing (:kind (:data match)))
                   (data/invalidate! (set (landing-dependencies match))))
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
        navigate! (fn [component & [pending-code? replace-shell?]]
                    (when (= navigation @*navigation)
                      (if component
                        (dispatch! (cond-> [(if replace-shell? :common/navigate :page/navigate)
                                            (cond-> (assoc-in match [:data :view] component)
                                              pending-code? (assoc-in [:data :controllers] nil))]
                                     replace-shell? (conj {:replace-shell? true})))
                        (dispatch! [:state [:error-page] a404/<not-found-page>]))))]
    (cond
      (nil? match)
      (do (dispatch! [:state [:error-page] a404/<not-found-page>])
          (dispatch! [:diag/new :error "404" "Not found"]))

      view (do
             (navigate! view)
             (when (= :landing (:kind (:data match)))
               (-> (data/ensure-all! (landing-dependencies match))
                   (.catch fail!))))

      module
      (let [ready (when (identical? load! l/load-code!) (l/code-spec module))
            restoring? (or (and (:hydrate? @restore/*context)
                                (= (:path match) (:path @ssr/*snapshot)))
                           (and (:back? @restore/*context) (restore/skip-enter?)))]
        ;; Commit the destination now, even on a cold code load. Managed views
        ;; replace their placeholders as data arrives; no SSR request is involved.
        ;; Initial hydration must select the real view, never a transient shell.
        ;; Leave server markup untouched until its module registers.
        ;; A cold history return also commits only once. An interim shell would
        ;; consume its saved scroll restoration before the real module arrives.
        (when (or ready (not restoring?))
          (navigate! (or (get-in ready [:view page])
                         (fn [] [shell/<page> (get-in match [:data :shell])]))
                     (nil? ready)))
        (-> (load! {:module module :view page :route match})
            ;; Finishing code replaces the destination shell in place. It must
            ;; not cancel/restart its transition or reset its scroll a second time.
            (.then #(when-not ready (navigate! (get-in % [:view page]) false (not restoring?))))
            (.catch fail!)))

      :else (navigate! nil))))

(defonce *route-request (atom 0))
(declare coercion-failed!)

(defn initial-parameters
  "Only the first matching network SSR route may use the server's typed values."
  [match snapshot initial? hydrating?]
  (when (and initial? hydrating?
             (= (:path match) (:path snapshot))
             (= (or (:query-params match) {}) (or (:query-params snapshot) {})))
    (:route-parameters snapshot)))

(defn on-nav [match route-history]
  (let [request (swap! *route-request inc)
        commit! (fn [match]
                  (when (= request @*route-request)
                    (status/recover! :route-coercion)
                    ;; Only same-document routing takes ownership from native restoration.
                    (when (pos? @*navigation)
                      (set! (.-scrollRestoration js/history) "manual"))
                    (when-let [path (:path match)] (ssr/leave! path))
                    (navigate! *navigation rf/dispatch l/load-code! match)))
        parameters (initial-parameters match @ssr/*snapshot
                                       (zero? @*navigation) (:hydrate? @restore/*context))]
    (cond
      parameters (commit! (assoc match :parameters parameters))
      (or (nil? match) (page-coercion/ready?)) (commit! match)
      :else
      (-> (l/acquire-code! :coercion)
          (.then (fn [_]
                   (when (= request @*route-request)
                     (try
                       (commit! (assoc match :parameters (coercion/coerce! match)))
                       (catch :default error (coercion-failed! match error))))))
          (.catch (fn [_]
                    (when (= request @*route-request)
                      (status/fail! :route-coercion "Page navigation could not initialize"
                                    "Check your connection and retry this page address."
                                    #(on-nav match route-history)))))))))

(defn ignore-anchor-click? [router event element ^goog.Uri uri]
  ;; Only intercepted internal links may update the pending fragment.
  (let [ignore? (rfh/ignore-anchor-click? router event element uri)]
    (when ignore?
      (rf/dispatch [:state [:fragment] (.getFragment uri)]))
    ignore?))

(defn coercion-failed! [match error]
  (swap! *route-request inc)
  (swap! *navigation inc)
  (let [issues (validation/problems (ex-data error))
        message (validation/message issues)]
    (rf/dispatch [:validation/report {:contract :route :issues issues}])
    (rf/dispatch [:state [:error-page]
                  (fn [] [error-view/<failure> "route" (:path match)
                          {:title "Invalid page address" :message message}
                          #(rfe/replace-state :home)])])))

(defn history-coercion-failed! [match error]
  (coercion-failed! match error)
  ;; Reitit rethrows after this callback. Replace its value-bearing exception so
  ;; browser error listeners/logs cannot retain raw query parameters.
  (throw (ex-info "Invalid page address" {:type :validation/route})))

(defn start! []
  ;; Preserve native restoration until a same-document navigation takes over.
  (rfe/start! router on-nav {:use-fragment false
                           :ignore-anchor-click? ignore-anchor-click?
                           :on-coercion-error history-coercion-failed!}))


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
