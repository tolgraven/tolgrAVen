(ns website.messaging-visual-fixture
  "Native Home messaging/profile owners with local managed reads and no writes."
  (:require [website.react :as r]
            [website.macros :as m]
            [website.editor-visual :as editor]
            [website.modules.home.views :as home]
            [website.navigation.routes :as routes]
            [website.component.restore :as restore]
            [website.component.data]
            [website.supabase.query :as query]
            [website.supabase.schema :as store-schema]
            [website.supabase.scoped :as scoped]
            [website.supabase.runtime :as supabase]
            [website.content.schema :as content-schema]
            [website.validation :as validation]
            [website.loader :as loader]
            [reitit.frontend.easy :as rfe]))

(def components
  #{"chat-populated" "chat-empty" "gpt-collapsed" "gpt-open"
    "codex-unavailable" "codex-populated"})

(def profile
  {:id "visual-messenger"
   :name "Visual Messenger"
   :avatar "/img/tolgrav-square.png"
   :bg-color "#635177"
   :comment-count 0
   :karma 0})

(def messages
  {"visual-message-first" {:user (:id profile)
                           :time 1791878400000
                           :text "Native **Chat** keeps its Markdown and avatar."}
   "visual-message-second" {:user (:id profile)
                            :time 1791964800000
                            :text "A second message preserves the real message order."}})

(def threads
  {"visual-thread-first" {:user (:id profile)
                          :time 1791878400000
                          :messages ["First local prompt" "First local reply"
                                     "Second local prompt" "Second local reply"]}
   "visual-thread-second" {:user (:id profile)
                           :time 1791964800000
                           :messages ["Another local prompt" "Another local reply"]}})

(def codex-profile
  {:display-name "Visual Reviewer"
   :profile-url "https://chatgpt.com/u/visual-reviewer"
   :bio "Fixed public profile data rendered by the original profile component."
   :days (mapv (fn [day]
                 {:date (str "2026-10-" (when (< day 10) "0") day)
                  :count (mod (* day 3) 8)
                  :level (mod day 5)})
               (range 1 15))})

(defonce *root (atom nil))
(defonce *cleanup (atom nil))
(defonce *observation (atom {:component nil, :reads #{}, :rejected 0, :mounted? false}))

(defn- module-for [component]
  (case component
    ("chat-populated" "chat-empty") :chat
    ("gpt-collapsed" "gpt-open") :gpt
    ("codex-unavailable" "codex-populated") :codex-profile
    (throw (js/Error. "Unknown messaging visual fixture"))))

(defn- queries-for [component]
  (let [module (module-for component)
        contract {"users" {(:id profile) profile}
                  "chat" {"messages" (if (= component "chat-empty") {} messages)}
                  "gpt-threads" threads}
        queries (case module
                  :chat [{:path-document [:chat :messages]}
                         (query/profile-query (:id profile))]
                  :gpt [{:path-collection [:gpt-threads]}
                        {:path-document [:gpt-threads :visual-thread-first]}
                        {:path-document [:gpt-threads :visual-thread-second]}
                        (query/profile-query (:id profile))]
                  :codex-profile [])]
    (into {} (map (fn [opts] [opts (query/query-contract contract opts)]) queries))))

(defn- check-data! [component values]
  (when (or (validation/explain store-schema/profile profile)
            (some #(validation/explain store-schema/chat-message %) (vals messages))
            (some #(validation/explain store-schema/private-data %) (vals threads))
            (validation/explain (:codex-profile content-schema/sections) codex-profile)
            (some (fn [[opts value]]
                    (or (validation/explain store-schema/query opts)
                        (validation/explain store-schema/query-result value))) values))
    (throw (js/Error. (str "Invalid native messaging fixture data: " component)))))

(defn install! [component]
  (when @*cleanup
    (throw (js/Error. "Dispose the previous messaging fixture before installing another")))
  (let [values (queries-for component)
        handlers (mapv (fn [[kind id]] [kind id (r/get-handler kind id)])
                       [[:sub :store/on-snapshot] [:fx :http-xhrio] [:fx :supabase/request]])
        old-ensure-code! supabase/ensure-code!
        *disposed? (atom false)
        reject! (fn [& _]
                  ;; Record no event arguments, credentials or private data.
                  (swap! *observation update :rejected inc)
                  (throw (js/Error. "Messaging fixtures reject provider acquisition, HTTP and writes")))
        cleanup! (fn cleanup! []
                   (when (compare-and-set! *disposed? false true)
                     (.removeEventListener js/window "pagehide" cleanup!)
                     (doseq [[kind id handler] handlers] (r/register-handler kind id handler))
                     (set! supabase/ensure-code! old-ensure-code!)
                     (reset! *cleanup nil)))]
    (check-data! component values)
    (reset! *observation {:component component, :reads #{}, :rejected 0, :mounted? false})
    (try
      ;; Preserve native Chat/GPT/user subscriptions and their result shaping.
      ;; Like the archive fixture, replace only the SDK-facing reader acquisition;
      ;; exact query results enter through the existing scoped result event.
      (r/reg-sub-raw :store/on-snapshot
        (fn [_ [_ opts]]
          (when (or @*disposed? (not (contains? values opts))) (reject!))
          (swap! *observation update :reads conj opts)
          (scoped/ensure-query! opts)))
      (doseq [[opts value] values]
        (r/dispatch-sync [:store/scoped (scoped/query-key opts) value]))
      (r/dispatch-sync [:state [:supabase-init] :ready])
      (when (= :gpt (module-for component))
        (r/dispatch-sync [:state [:active-user] profile]))
      (when (= :codex-profile (module-for component))
        ;; The optional profile is not published by CMS (:content []). Native
        ;; :content reads already return installed data without acquiring it.
        ;; Seed that exact schema-valid field, retaining the real subscription.
        (r/dispatch-sync [:component-data/install [:content :codex-profile]
                          (if (= component "codex-unavailable")
                            (dissoc codex-profile :days) codex-profile)]))
      (r/reg-fx :http-xhrio reject!)
      (r/reg-fx :supabase/request reject!)
      (set! supabase/ensure-code! reject!)
      (.addEventListener js/window "pagehide" cleanup! #js {:once true})
      (reset! *cleanup cleanup!)
      (catch :default error (cleanup!) (throw error)))))

(defn ^:export draft! [text]
  (when-not (string? text) (throw (js/Error. "Fixture draft must be text")))
  (let [path (case (module-for (:component @*observation))
               :chat [:chat]
               :gpt [:gpt-thread "visual-thread-first"]
               (throw (js/Error. "Profile fixtures do not own a composer")))]
    ;; Local form intent only; never dispatch Enter, Send or New thread.
    (r/dispatch-sync [:form-field path text])))

(defn ^:export thread-messages! [messages]
  (when-not (= :gpt (module-for (:component @*observation)))
    (throw (js/Error. "Only the local GPT fixture owns thread messages")))
  (let [thread (assoc (get threads "visual-thread-first") :messages (js->clj messages))
        opts {:path-document [:gpt-threads :visual-thread-first]}]
    (when (validation/explain store-schema/private-data thread)
      (throw (js/Error. "Invalid local GPT messages")))
    (r/dispatch-sync [:store/scoped (scoped/query-key opts)
                      {:id "visual-thread-first", :data thread}])))

(defn ^:export state []
  (clj->js {:component (:component @*observation)
            :mounted (:mounted? @*observation)
            :reads (mapv scoped/query-key (sort-by pr-str (:reads @*observation)))
            :rejected (:rejected @*observation)
            :chat-draft @(r/sub [:get :state :form-field :chat])
            :thread-draft @(r/sub [:get :state :form-field :gpt-thread "visual-thread-first"])}))

(m/defpage <page> [component]
  (r/use-effect
    (fn []
      (swap! *observation assoc :mounted? true)
      (fn [] (swap! *observation assoc :mounted? false)))
    [])
  ;; Use the ordinary Home section/module and native full-page boundary, as on
  ;; the actual site. There are no copied message/profile children or styles.
  [home/<layout> {:identity "home"
                 :sections [{:identity (name (module-for component))
                             :component (name (module-for component))}]}])

(defn ^:export dispose! []
  (when-let [root @*root]
    (r/unmount root)
    (reset! *root nil))
  (when-let [cleanup! @*cleanup] (cleanup!)))

(defn ^:export init! [component]
  (module-for component)
  (-> (js/Promise.resolve nil)
      (.then (fn [_]
               (when (or @*root @*cleanup)
                 (throw (js/Error. "Messaging fixture already owns this document")))
               (js/fetch "/fixtures/editor-seed.json")))
      (.then (fn [response]
               (when-not (.-ok response) (throw (js/Error. "Messaging fixture seed is unavailable")))
               (.json response)))
      (.then (fn [seed]
               (rfe/start! routes/router (fn [_ _]) {:use-fragment false})
               (r/dispatch-sync [:editor-visual/install (js->clj seed :keywordize-keys true) false])
               (install! component)
               (restore/begin! {})
               (loader/load! {:module :markdown})))
      (.then (fn [_] (loader/load! {:module (module-for component)})))
      (.then (fn [_]
               (let [root (r/create-root (.getElementById js/document "app"))]
                 (reset! *root root)
                 (r/render root [:main#main.main-content.perspective-top [<page> component]]))))
      (.catch (fn [error] (dispose!) (throw error)))))
