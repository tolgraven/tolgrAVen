(ns website.editor-visual
  "Deterministic browser component fixture for before/after screenshot review."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.macros :refer-macros [defc defpage]]
            [website.supabase.runtime :as supabase]
            [website.validation :as validation]
            [website.modules.user.schema :as user-schema]
            [website.modules.user.subs]
            [website.modules.content-editor.events]
            [website.modules.content-editor.schema :as editor-schema]
            [website.events]
            [website.subs]
            [website.db :as db]
            [website.navigation.routes :as routes]
            [reitit.core :as reitit]
            [reitit.frontend.easy :as rfe]
            [website.component.restore :as restore]
            [website.loader :as loader]
            [website.components.shell :as shell]
            [website.components.heading :as heading]
            [website.components.image :as image]
            [website.components.media :as media]
            [website.modules.home.sections :as home]
            [website.modules.cv.views :as cv]
            [website.modules.user.views :as user]
            [website.content.contract :as content]))

(def profile {:id "visual-admin"
              :name "Visual reviewer"
              :email "review@example.invalid"
              :avatar nil
              :bg-color nil
              :comment-count 0
              :karma 0
              :roles ["admins"]})
(r/reg-event-db :editor-visual/install
  (fn [_ [_ seed edit?]]
    (-> db/data
        (assoc :content (content/normalize-content seed)
               :common/route (reitit/match-by-path routes/router "/"))
        (assoc-in [:state :gallery :loaded] true)
        (assoc-in [:state :active-user] (when edit? profile))
        (assoc-in [:state :content-editor] {:owner "visual-admin", :enabled? edit?, :fields {}}))))

(defn section [key enhanced?]
  @(r/subscribe [(if enhanced? :content/view :content) [key]]))

(defn <fixture> [component enhanced?]
  (let [content' #(section % enhanced?)]
    [:<>
     [:main.main-content.perspective-top {:id "main"}
     (case component
       "intro" [home/<intro> (content' :intro)]
       "services" [home/<services> (content' :services)]
       "story" [home/<story> (content' :story)]
       "moneyshot" [media/<moneyshot> (content' :moneyshot)]
       "interlude" [media/<interlude> (content' :interlude) 0]
       "gallery" [media/<gallery> (content' :gallery)]
       "header" [shell/<header> (content' :header)]
       "footer" [shell/<footer-full> (content' :footer)]
       "sticky-footer" [shell/<footer> (content' :footer)]
       "post-footer" [shell/<post-footer> (content' :post-footer)]
       "cv" [cv/<cv>]
       "blog-heading" [heading/<banner> (:heading (content' :blog))]
       "docs-heading" [heading/<banner> (:heading (content' :docs))]
       "image" [image/<picture> (assoc (get (content' :gallery) 0) :class "media" :style {:width "30rem", :max-width "100%"})]
       "admin" [user/<admin> profile]
       [:p "Unknown component"])]
     (when (and enhanced? (= :edit @(r/subscribe [:content/mode])))
       (loader/component-vector {:module :content-editor, :view :toolbar, :load-on :immediate} []))]))

(def account-profile
  (assoc profile :avatar "/img/tolgrav-square.png"))

(defonce *account-effects (atom []))

(defn- reject-account-effect! [operation]
  ;; Never retain event arguments, credentials, files or URLs in diagnostics.
  (swap! *account-effects conj operation)
  (throw (js/Error. "Account visual fixtures reject Auth, uploads and writes")))

(defn- install-account! []
  (let [settings {:admin-url nil, :writable? true}
        editor-state {:owner (:id account-profile)
                      :enabled? false
                      :settings settings
                      :fields {}}
        handlers (mapv (fn [[kind id]] [kind id (r/get-handler kind id)])
                       [[:fx :content-editor/request] [:fx :content-editor/upload]
                        [:fx :supabase/request] [:fx :http-xhrio] [:fx :ls] [:cofx :ls]])
        original-ensure-code! supabase/ensure-code!
        *saved (atom {})
        *disposed? (atom false)
        cleanup! (fn []
                   (when (compare-and-set! *disposed? false true)
                     (r/clear-global-interceptor :account-visual/read-only)
                     (set! supabase/ensure-code! original-ensure-code!)
                     (doseq [[kind id handler] handlers]
                       (r/register-handler kind id handler))
                     (reset! *saved {})))]
    (when (or (validation/explain user-schema/active-user account-profile)
              (validation/explain [:map [:roles [:vector :string]]] account-profile)
              (validation/explain editor-schema/state editor-state))
      (throw (js/Error. "Invalid account visual profile or settings")))
    (reset! *account-effects [])
    (try
      ;; Direct User handlers call the Auth facade rather than :supabase/request.
      ;; Reject these native intents before their handler, and guard acquisition.
      (r/reg-global-interceptor
        (r/->interceptor
          :id :account-visual/read-only
          :before (fn [context]
                    (when (#{:user/create-account :user/sign-in :user/sign-out
                             :user/request-login :user/request-register
                             :user/upload-avatar :user/set-field :user/request-change-password
                             :content-editor/save-all :content-editor/upload :content-editor/cv-create}
                            (first (get-in context [:coeffects :event])))
                      (reject-account-effect! :write))
                    context)))
      (set! supabase/ensure-code! (fn [& _] (reject-account-effect! :provider)))
      (r/reg-fx :content-editor/request
        (fn [{:keys [method uri success]}]
          (if (and (= :get method) (= "/api/content/editor" uri))
            (do (swap! *account-effects conj :settings-read)
                (r/dispatch (conj success settings)))
            (reject-account-effect! :write))))
      (r/reg-fx :content-editor/upload (fn [_] (reject-account-effect! :write)))
      (r/reg-fx :supabase/request (fn [_] (reject-account-effect! :provider)))
      (r/reg-fx :http-xhrio (fn [_] (reject-account-effect! :http)))
      ;; Native input blur persists through :ls/store-val. Keep this disposable
      ;; draft transport in memory, never in the shared browser's localStorage.
      (r/reg-cofx :ls #(assoc % :ls @*saved))
      (r/reg-fx :ls (fn [value] (reset! *saved value)))
      (r/dispatch-sync [:state [:supabase-init] :ready])
      (r/dispatch-sync [:state [:active-user] account-profile])
      (r/dispatch-sync [:state [:login-show-password] false])
      (r/dispatch-sync [:user/close-ui])
      ;; The component cleanup also handles failed captures when its frame exits.
      (.addEventListener js/window "pagehide" cleanup! #js {:once true})
      (fn []
        (.removeEventListener js/window "pagehide" cleanup!)
        (cleanup!))
      (catch :default error (cleanup!) (throw error)))))

(defc <account-owner> [child]
  ;; No DOM wrapper: the real shell still owns page-sibling User placement.
  (r/use-layout-effect install-account! [])
  child)

(defn ^:export account-state []
  (clj->js
    {:id (:id @(r/subscribe [:user/active-user]))
     :admin? @(r/subscribe [:user/has-role? :admins])
     :section @(r/subscribe [:user/active-section])
     :mode @(r/subscribe [:content/mode])
     :settings-reads (count (filter #{:settings-read} @*account-effects))
     :rejected (count (remove #{:settings-read} @*account-effects))}))

(defn ^:export account-open! []
  (r/dispatch-sync [:user/open-ui]))

(defn ^:export account-close! []
  (r/dispatch-sync [:user/close-ui]))

(defn ^:export account-draft! [kind]
  ;; Fake local form values only; never call the write/credential buttons.
  (case kind
    "username" (r/dispatch-sync [:form-field [:change :username] "Visual reviewer draft"])
    "password" (do (r/dispatch-sync [:form-field [:change-password :current] "fixture-current-only"])
                   (r/dispatch-sync [:form-field [:change-password :new] "fixture-new-only"]))
    "avatar" (r/dispatch-sync [:form-field [:change :avatar-url] "/img/tolgrav-square.png"])))

(defonce *cms-effects (atom []))

(defn- reject-cms-effect! [operation]
  (swap! *cms-effects conj {:operation operation})
  (throw (js/Error. "CMS visual fixtures do not acquire providers, upload or publish")))

(defn install-cv-visit!
  ([] (install-cv-visit! (fn [_])))
  ([save!]
   ;; Only disposable visual CV documents use this read/write transport. Keep
   ;; the native mount, visit-restored, durable claim and delayed events intact;
   ;; neither the legacy store nor component.storage reads a user's disk.
   (let [*saved (atom {})]
     (r/reg-cofx :ls #(assoc % :ls @*saved))
     (r/reg-fx :ls
       (fn [value]
         (save! value)
         (when-not (= {:cv-visited true} value)
           (throw (js/Error. "CV visual fixtures only save their in-memory visit marker")))
         (reset! *saved value)))
     (r/reg-fx :cv/restore-visit
       (fn [generation]
         (r/dispatch [:cv/visit-restored generation (true? (:cv-visited @*saved))]))))))

(defn install-cms! [component]
  ;; Only the new disposable CMS cases install these boundaries. The manual
  ;; editor-visual comparison and its opt-in loopback transport stay unchanged.
  (let [settings {:admin-url nil, :writable? true}
        editor-state {:owner (:id profile)
                      :enabled? false
                      :settings settings
                      :fields {}}]
    (when (or (validation/explain user-schema/active-user profile)
              (validation/explain editor-schema/state editor-state))
      (throw (js/Error. "Invalid CMS visual account or editor settings")))
    (reset! *cms-effects [])
    (r/reg-fx :content-editor/request
      (fn [{:keys [method uri success]}]
        (if (and (= :get method) (= "/api/content/editor" uri))
          (do
            (swap! *cms-effects conj {:operation :settings-read})
            (r/dispatch (conj success settings)))
          (reject-cms-effect! :write))))
    (r/reg-fx :content-editor/upload (fn [_] (reject-cms-effect! :write)))
    (r/reg-fx :supabase/request (fn [_] (reject-cms-effect! :provider)))
    (r/reg-fx :http-xhrio (fn [_] (reject-cms-effect! :http)))
    ;; cv/<cv> always records its visit through this existing event/effect.
    ;; Keep its native mounted demo, but never read or persist a user's storage.
    (r/reg-cofx :ls #(assoc % :ls {}))
    (r/reg-fx :ls
      (fn [value]
        (if (= {:cv-visited true} value)
          (swap! *cms-effects conj {:operation :local-visit})
          (reject-cms-effect! :write))))
    (when (= component "cms-cv")
      ;; Retain CMS's strict write accounting around the same local CV marker.
      (install-cv-visit! (r/get-handler :fx :ls)))
    (r/dispatch-sync [:state [:supabase-init] :ready])
    (r/dispatch-sync [:state [:active-user] profile])
    ;; The real owner/settings result and toggle events establish edit mode.
    ;; Queue toggle after the effect's settings event, without a second store.
    (r/dispatch-sync [:content-editor/open])
    (r/dispatch [:content-editor/toggle])))

(defn ^:export cms-state []
  (let [editor @(r/subscribe [:get :state :content-editor])]
    (when (validation/explain editor-schema/state editor)
      (throw (js/Error. "Invalid native CMS visual draft")))
    {:mode @(r/subscribe [:content/mode])
     :admin? @(r/subscribe [:user/has-role? :admins])
     :active-user-id (:id @(r/subscribe [:user/active-user]))
     :fields (:fields editor)
     :cv-entry (:cv-entry editor)
     :services-caption @(r/subscribe [:content [:services :caption]])
     :settings-reads (count (filter #(= :settings-read (:operation %)) @*cms-effects))
     :writes (count (filter #(= :write (:operation %)) @*cms-effects))
     :provider-requests (count (filter #(#{:provider :http} (:operation %)) @*cms-effects))
     :local-visits (count (filter #(= :local-visit (:operation %)) @*cms-effects))}))

(defpage <cms-services-page> []
  [home/<services> (section :services true)])

(defpage <cms-gallery-page> []
  [media/<gallery> (section :gallery true)])

(defn <cms-fixture> [component]
  [:<>
   [:main#main.main-content.perspective-top
    ;; CV owns its page and managed dependency. Home sections retain their
    ;; native page-root boundary rather than stretching inside main's grid.
    (case component
      "cms-services" [<cms-services-page>]
      "cms-gallery" [<cms-gallery-page>]
      "cms-cv" [cv/<page>])]
   ;; Match components/page: the fixed toolbar is outside main's perspective.
   (loader/component-vector {:module :content-editor, :view :toolbar, :load-on :immediate} [])])

(defn ^:export fixture-transport! []
  ;; This test-only transport talks to the disposable loopback CMS via the
  ;; fixture server. Production continues using the real verified session adapter.
  (r/reg-fx :content-editor/request
    (fn [{:keys [method uri data success failure]}]
      (-> (js/fetch uri
            (clj->js (cond-> {:method (string/upper-case (name method))}
                       (and data (not (instance? js/FormData data)))
                       (assoc :headers {"Content-Type" "application/json"})
                       data (assoc :body (if (instance? js/FormData data) data
                                           (js/JSON.stringify (clj->js data)))))))
          (.then (fn [response]
                   (-> (.json response)
                       (.then (fn [body]
                                (r/dispatch (conj (if (.-ok response) success failure)
                                                  (if (.-ok response) (js->clj body :keywordize-keys true)
                                                    {:response (js->clj body :keywordize-keys true)}))))))))
          (.catch #(r/dispatch (conj failure {:message (.-message %)})))))))

(defn ^:export init! [enhanced?]
  ;; Header href subscriptions use the installed history's router. The fixture
  ;; keeps navigation inert while using that same public link-generation API.
  (rfe/start! routes/router (fn [_ _]) {:use-fragment false})
  (let [params (js/URLSearchParams. (.-search js/location))
        component (or (.get params "component") "intro")
        edit? (= "true" (.get params "edit"))]
    (-> (js/fetch (if (= "true" (.get params "live")) "/fixtures/editor-live.json" "/fixtures/editor-seed.json"))
        (.then #(.json %))
        (.then (fn [seed]
                 (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) edit?])
                 (restore/begin! {:hydrate? false, :back? true})
                 (r/render (r/create-root (.getElementById js/document "app")) [<fixture> component enhanced?]))))))
