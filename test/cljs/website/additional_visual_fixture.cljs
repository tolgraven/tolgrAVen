(ns website.additional-visual-fixture
  "Additional native consumers; only disposable read transports are replaced."
  (:require [clojure.string :as string]
            [reitit.core :as reitit]
            [website.component.data :as data]
            [website.components.media :as media]
            [website.components.oembed :as oembed]
            [website.components.oembed.contract :as oembed-schema]
            [website.editor-visual :as editor]
            [website.loader :as loader]
            [website.macros :refer-macros [defc]]
            [website.modules.blog.comment-views :as comments]
            [website.modules.blog.schema :as blog-schema]
            [website.modules.search.model :as search-model]
            [website.navigation.routes :as routes]
            [website.react :as r]
            [website.supabase.runtime :as supabase]
            [website.supabase.schema :as store-schema]
            [website.supabase.scoped :as scoped]
            [website.validation :as validation]))

(def component-names
  #{"authored-highlight-config" "authored-inline-search" "authored-comment-quote" "authored-frame"
    "home-soundcloud-placeholder" "home-soundcloud-ready" "home-video-interlude"
    "experiments-parallax" "experiments-liquid-glass" "experiments-model-viewer"
    "inspector-data" "inspector-error"})

(defn experiments? [component]
  (string/starts-with? component "experiments-"))

(defn prepare!
  "Load real owners before installing fixed transports; do not copy their views."
  [component]
  (let [owners (cond
                 (experiments? component) [:maps :test]
                 (= component "authored-highlight-config") [:markdown-components :settings]
                 (= component "authored-inline-search") [:markdown-components :search :blog-preview]
                 (= component "authored-comment-quote") [:markdown-components :blog]
                 (= component "authored-frame") [:markdown-components]
                 (#{"inspector-data" "inspector-error"} component) [:data-inspector]
                 :else [])]
    (-> (reduce (fn [pending owner]
                  (.then pending (fn [_] (loader/acquire-code! owner))))
                (js/Promise.resolve nil) owners)
        (.then (fn [_]
                 ;; Native :depends reads the SDK cache before the pooled reader.
                 ;; Install its real code/API before rejecting provider acquisition;
                 ;; the scoped local result below then satisfies that same cache.
                 (when (= component "authored-comment-quote")
                   (supabase/ensure-code!)))))))

(defonce *cleanup (atom nil))
(defonce *reads (atom []))
(defonce *rejected (atom 0))

(defn- reject! [& _]
  (swap! *rejected inc)
  (throw (js/Error. "Additional visual fixtures reject unexpected provider I/O and writes")))

(defn install!
  "Run after common seed/font/router initialization and prepare!. Each document
   owns its adapters until pagehide or unmount. Input records use owning schemas."
  [component {:keys [comment-row search-response]}]
  (when @*cleanup (@*cleanup))
  (reset! *reads [])
  (reset! *rejected 0)
  (let [handlers (mapv (fn [[kind id]] [kind id (r/get-handler kind id)])
                       [[:sub :store/on-snapshot] [:fx :http-xhrio] [:fx :supabase/request]
                        [:cofx :ls] [:fx :ls]
                        [:sub :search/query] [:sub :search/response] [:sub :search/pending?]
                        [:sub :search/error] [:sub :search/selected] [:sub :search/get]])
        original-ensure! supabase/ensure-code!
        original-url (get @data/*sources :url)
        *saved (atom {})
        *disposed? (atom false)
        cleanup! (fn cleanup! []
                   (when (compare-and-set! *disposed? false true)
                     (.removeEventListener js/window "pagehide" cleanup!)
                     (doseq [[kind id handler] handlers] (r/register-handler kind id handler))
                     (when original-url (data/register-source! :url original-url))
                     (set! supabase/ensure-code! original-ensure!)
                     (reset! *cleanup nil)))]
    (try
      (r/reg-fx :http-xhrio reject!)
      (r/reg-fx :supabase/request reject!)
      (set! supabase/ensure-code! reject!)
      ;; Authored preference controls must never write the shared profile's disk.
      (r/reg-cofx :ls #(assoc % :ls @*saved))
      (r/reg-fx :ls #(reset! *saved %))
      (when (experiments? component)
        (let [tab (keyword (subs component (count "experiments-")))
              route (reitit/match-by-path routes/router (str "/test/" (name tab)))]
          (when-not route (throw (js/Error. "Native experiments route is unavailable")))
          (r/dispatch-sync [:set [:common/route] route])
          (r/dispatch-sync [:state [:experiments] tab])
          (r/dispatch-sync [:state [:elems] [1 2 3]])))
      (when (= component "authored-inline-search")
        (when (validation/explain search-model/result search-response)
          (throw (js/Error. "Invalid authored Search response")))
        (r/reg-sub :search/query (fn [_ _] (:query search-response)))
        (r/reg-sub :search/response (fn [_ _] search-response))
        (r/reg-sub :search/pending? (fn [_ _] false))
        (r/reg-sub :search/error (fn [_ _] nil))
        (r/reg-sub :search/selected (fn [_ _] -1))
        (r/reg-sub :search/get (fn [_ _] 0)))
      (when (= component "authored-comment-quote")
        (let [query (comments/quoted-comment-query (:parent-post comment-row) (:id comment-row))
              value {:docs [{:id (:id comment-row), :data comment-row}]}]
          (when (or (validation/explain blog-schema/displayed-comment comment-row)
                    (validation/explain store-schema/query query)
                    (validation/explain store-schema/query-result value))
            (throw (js/Error. "Invalid native quoted comment fixture")))
          (r/reg-sub-raw :store/on-snapshot
            (fn [_ [_ query']]
              (when-not (= query query') (reject!))
              (swap! *reads conj :quoted-comment)
              (scoped/ensure-query! query')))
          (r/dispatch-sync [:store/scoped (scoped/query-key query) value])
          (r/dispatch-sync [:state [:supabase-init] :ready])))
      (when (#{"home-soundcloud-placeholder" "home-soundcloud-ready"} component)
        (r/dispatch-sync [:state [:booted :soundcloud] (= component "home-soundcloud-ready")])
        (when (= component "home-soundcloud-ready")
          (let [{:keys [artist tunes]} (editor/section :soundcloud false)
                requests (into {}
                               (map (fn [tune]
                                      (let [url (str "https://soundcloud.com/" artist "/" tune)
                                            result {:html ""
                                                    :title tune
                                                    :height 166
                                                    :player-src (str "https://w.soundcloud.com/player/?url="
                                                                     (js/encodeURIComponent url))}]
                                        (when (validation/explain oembed-schema/result result)
                                          (throw (js/Error. "Invalid SoundCloud read response")))
                                        [(:url (oembed/dependency url)) result])) tunes))]
            (data/register-source! :url
              {:load! (fn [{:keys [url]}]
                        (if-let [result (get requests url)]
                          (do (swap! *reads conj :oembed) (js/Promise.resolve result))
                          (reject!)))}))))
      (.addEventListener js/window "pagehide" cleanup! #js {:once true})
      (reset! *cleanup cleanup!)
      (catch :default error (cleanup!) (throw error)))))

(def authored-sources
  {"authored-highlight-config"
   (str "Before the widgets.\n\n"
        "<Component component=\"website.components.content.box/<highlight-box>\" title=\"Published aside\" text=\"An aside worth noticing\" tone=\"tip\" layout-width=\"20rem\" layout-flow=\"right\" />\n\n"
        "Ordinary prose retains the native layout around the floated aside.\n\n"
        "<Component component=\"website.modules.settings.views/<config-table>\" title=\"Appearance controls\" />\n")
   "authored-inline-search"
   "## Search published content\n\n<Component component=\"website.modules.markdown-components.widgets/<site-search>\" />\n"
   "authored-comment-quote"
   "## Quoted discussion\n\n<Component component=\"website.modules.markdown-components.widgets/<comment-quote>\" post-id=\"json:42\" comment-id=\"visual-comment\" />\n"
   "authored-frame"
   "## Embedded document\n\n<Component component=\"website.components.content.frame/<frame>\" src=\"/fixtures/editor-frame.html\" title=\"Local authored frame\" layout-height=\"auto\" />\n"})

(def inspector-value
  {:route "/blog/post/42/comment/visual-comment"
   :module :blog
   :ready? true
   :records [{:id 42, :title "Native formatted content"}
             {:id "visual-comment", :score 3}]
   :details {:empty [], :missing nil}})

(declare <inspector-error>)

(defc <fixture> [component]
  (r/use-effect (fn [] (fn [] (when @*cleanup (@*cleanup)))) [])
  (cond
    (contains? authored-sources component)
    (loader/component-vector {:module :markdown, :view :parse, :load-on :immediate}
                             [(get authored-sources component)])
    (#{"home-soundcloud-placeholder" "home-soundcloud-ready"} component)
    [oembed/<soundcloud>]
    (= component "home-video-interlude")
    ;; Keep the seed's autoplay, play-pauser, delayed start and native poster.
    ;; A static screenshot is insufficient to verify this running video branch.
    [media/<interlude> (editor/section :interlude false) 0]
    (experiments? component)
    (loader/component-vector {:module :test, :view :page, :load-on :immediate} [])
    (= component "inspector-data")
    (loader/component-vector {:module :data-inspector, :view :data, :load-on :immediate}
                             ["Native formatted value" inspector-value])
    (= component "inspector-error")
    [<inspector-error>]))

(defc <inspector-error> []
  :let [*error (r/atom {:error (ex-info "Native diagnostic details" {:module :blog})
                      :stack "at website.modules.blog.views/<blog-page>\nat website.component.lifecycle/mount"})]
  (loader/component-vector {:module :data-inspector, :view :error-toggle, :load-on :immediate}
                           ['website.modules.blog.views '<blog-page> *error {:route "/blog/post/42", :retry? true}]))

(defn ^:export observation []
  (clj->js {:reads @*reads, :rejected @*rejected}))
