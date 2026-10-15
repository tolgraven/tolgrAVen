(ns website.navigation.routes
  #?(:cljs (:require
    [website.component.registry]
    [website.site :as site]
    [website.validation :as validation]
    [website.validation.runtime :as validation-runtime]
    [website.schema.http :as schemas]
    [website.schema.page-coercion :as page-coercion]
    [reitit.coercion :as coercion]
    [website.service-status :as status]
    [website.navigation.transition]
    [website.navigation.component-routes :as component-routes]
    [website.navigation.schema :as navigation-schema]
    [website.macros :refer-macros [defc defpage]]
    [website.ssr.client :as ssr] [website.react :as r]
    [reitit.frontend :as reitit]
    [reitit.frontend.history :as rfh]
    [reitit.frontend.easy :as rfe]
    [website.diagnostics.routes :as diagnostics]
    #?(:module/blog [website.modules.blog.pages :as blog])
    #?(:module/blog-editor [website.modules.blog-editor.pages :as blog-editor])
    #?(:module/cv [website.modules.cv.pages :as cv])
    #?(:module/docs [website.modules.docs.pages :as docs])
    #?(:module/github [website.modules.github.pages :as github])
    #?(:module/neovim [website.modules.neovim.pages :as neovim])
    #?(:module/neovim [website.modules.neovim.policy :as neovim-policy])
    [website.loader :as l]
    [website.component.data :as data]
    [website.content.contract :as content]
    [website.components.error :as error-view]
    [website.components.page-shell :as shell]
    [website.component.restore :as restore]
    [website.components.ui.diagnostics :as ui-diagnostics]
    [website.components.ui.layout :as ui-layout]
    #?(:module/home [website.modules.home.pages :as home])
    [website.components.not-found :as a404] )))

#?(:cljs
   (do
(defpage <log-page>
  {:depends [{:source :strapi :keys [:common]}]}
  []
  [ui-layout/<with-heading> [:common :banner-heading]
   [ui-diagnostics/<log> (r/subscribe [:option [:log]])
    (r/subscribe [:get :diagnostics])]
   {:title "Log" :tint "blue"}])

(def router
  (reitit/router
    (into ["" {:coercion schemas/coercion :parameters {:query schemas/page-query}
               :controllers [{:parameters {:query [:userBox :settingsBox]} ; ok so this how done. but surely will get unwieldy af?
                     :start (fn [{:keys [query]}]    ; and how get to update url with changes...
                              (case (:userBox query) ; was thinking "have :user/open-ui passing true/false and no case but would be spammy"
                               true (r/dispatch [:user/open-ui])
                               (false nil) (r/dispatch [:user/close-ui]))
                              (case (:settingsBox query)
                                true (do (r/dispatch [:state [:settings :panel-open] true])
                                           (r/dispatch [:scroll/to-top-and-arm-restore]))
                                (false nil) (r/dispatch [:state [:settings :panel-open] false]))) ; well this being on start it wouldn't be open anyways
                     :stop (fn [{:keys [query]}]    ; why is this being run without leaving page?
                             )}]}]
          (remove nil?)
          (site/routes (concat #?(:module/home home/spec :default nil)
                  [#?(:module/cv (into [""] cv/spec))
                   #?(:module/docs (into [""] docs/spec))
                   #?(:module/neovim (into [""] neovim/spec))
                   #?(:module/github (when (seq github/spec) (into [""] github/spec)))
                   #?(:module/blog (into [""] blog/spec))
                   #?(:module/blog-editor (into [""] blog-editor/spec))
                   ["/log" {:name :log :view #'<log-page>}]
                   #?(:module/test ["/test"
      ["" {:name :test
           :module :test
           :page :page}]
      ["/:tab"
       {:name :test-tab
        :module :test
        :page :page
       :controllers [{:parameters {:path [:tab]} ; seems like a nice middle ground of using routing and urls but not fully integrating (needing these views available/known here for example). So, keep doing sub tabs like this?
                      :start (fn [{:keys [path]}]
                               (r/dispatch [:state [:experiments] (keyword (:tab path))])
                               (r/dispatch [:exception [:experiments] nil]))}]}]])
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
                                   (r/dispatch [:oauth/store-token-twitter
                                                 (:oauth_token query)
                                                 (:oauth_token_secret query)])
                                   (r/dispatch [:diag/new :error "Twitter auth"
                                                 "Error authenticating"])))}]}]]
                   ["/not-found" {:name :not-found :view #'a404/<not-found-page>}]])))
    (merge diagnostics/options {:validate validation-runtime/validate-routes!})))

;; A late module response must never navigate back over a newer URL.
(defonce *navigation (atom 0))

(defn landing-dependencies [match]
  [{:source :strapi
    :keys (content/keys-for-route (or (get-in match [:data :page-route])
                                    (get-in match [:data :name])))}])

(r/reg-event-fx :navigation/activate-component
  (fn [{:keys [db]} _]
    ;; Native Reitit controllers start as the page route commits. Reading the
    ;; accepted match here avoids racing a native transition's async update.
    (when-let [match (when (get-in db [:common/route :data :component-route]) (:common/route db))]
      {:dispatch [:loader/activate (select-keys (get-in match [:data :component-route]) [:module])]
       :navigation/load-component match})))

(r/reg-fx :navigation/load-component
  (fn [match]
    (let [target (get-in match [:data :component-route])
          ;; The mounted host supplies the target view's arguments and data.
          ;; Acquiring its code must not invoke that view with an empty argv.
          options (select-keys target [:module])]
      (-> (l/load-code! options)
          (.then (fn [_] (r/dispatch [:navigation/component-loaded match])))
          (.catch (fn [_] (r/dispatch [:navigation/component-failed match])))))))

(r/reg-event-fx :navigation/component-loaded
  {:args [:tuple navigation-schema/route]}
  (fn [{:keys [db]} [_ match]]
    (when (= (:path match) (get-in db [:common/route :path]))
      (when-let [event (component-routes/action match)] {:dispatch event}))))

(r/reg-event-fx :navigation/component-failed
  {:args [:tuple navigation-schema/route]}
  (fn [{:keys [db]} [_ match]]
    (when (= (:path match) (get-in db [:common/route :path]))
      {:dispatch [:diag/new :error "Destination could not be loaded"
                  "Retry the component or open its permalink again."]})))

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

(r/reg-event-fx :navigation/route-ready
  {:args [:tuple navigation-schema/route]}
  (fn [{:keys [db]} [_ match]]
    (let [previous (:common/route db)
          view (get-in (l/code-spec (get-in match [:data :module]))
                       [:view (get-in match [:data :page])])
          component-only? (and (get-in match [:data :component-route])
                               (not (get-in db [:state :browser-nav :got-nav]))
                               view
                               (= view (get-in previous [:data :view]))
                               (component-routes/same-page? previous match))]
      (if component-only?
        ;; A component tail changes intent within this mounted host. Retain its
        ;; restoration/appearance context and skip page acquisition/transition.
        {:db (assoc-in db [:state :scroll :component-focus] {:path (:path match), :scroll? true})
         :dispatch [:common/navigate (assoc-in match [:data :view] view) nil]}
        {:navigation/page match}))))

(r/reg-fx :navigation/page
  (fn [match]
    (when-let [path (:path match)] (ssr/leave! path))
    (navigate! *navigation r/dispatch l/load-code! match)))

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
                    (r/dispatch [:navigation/route-ready match])))
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
  ;; Reitit's predicate ignores the anchor's *native default action* when true.
  ;; Document navigation must therefore return false, preserving the real load.
  (let [ignore? (and (not= "true" (.getAttribute element "data-document-navigation"))
                     (not #?(:module/neovim (neovim-policy/document-navigation? js/window.crossOriginIsolated (.getPath uri))
                             :default false))
                     (rfh/ignore-anchor-click? router event element uri))]
    (when ignore?
      (r/dispatch [:state [:fragment] (.getFragment uri)]))
    ignore?))

(defn coercion-failed! [match error]
  (swap! *route-request inc)
  (swap! *navigation inc)
  (let [issues (validation/problems (ex-data error))
        message (validation/message issues)]
    (r/dispatch [:validation/report {:contract :route :issues issues}])
    (r/dispatch [:state [:error-page]
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
))
