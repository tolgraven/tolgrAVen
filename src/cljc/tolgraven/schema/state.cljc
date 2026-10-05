(ns tolgraven.schema.state
  "Application-owned state. Native events, refs and exceptions are intentionally
   opaque; feature data is described by its owning contract."
  (:require [tolgraven.schema.common :as c]
            [tolgraven.supabase.schema :as store]))

(def flags [:map-of c/id :boolean])
(def form-fields
  (c/optional-map {:chat [:maybe :string] :gpt-thread [:map-of c/id :string]
                   :login (c/optional-map {:email :string :password :string})
                   :change-password (c/optional-map {:current :string :new :string})
                   :contact (c/optional-map {:name :string :email :string :title :string :message :string})
                   :post-blog [:maybe (c/optional-map {:title :string :text :string
                                                       :tags [:or :string c/strings]})]
                   :write-comment [:map-of c/path [:maybe (c/optional-map {:title [:maybe :string] :text [:maybe :string]})]]}))
(def link-candidate
  (c/optional-map {:candidate-id c/id :container-id c/id :url :string :title :string
                   :trust [:enum :trusted :user :untrusted]
                   :status [:enum :preview :leaving :returning :expanding :navigating :navigate :expanded]}))
(def link-preview
  (c/optional-map {:containers [:maybe [:map-of c/id [:map [:candidates [:sequential link-candidate]] [:count c/nonnegative]]]]
                   :active [:maybe link-candidate] :prefetch [:maybe [:map-of :string [:enum :queued :prefetched]]]
                   :prefetch-queue [:sequential link-candidate]}))
(def init-entry (c/optional-map {:inited? :boolean :args [:maybe [:sequential :any]]}))
(def state
  (c/optional-map
   {:link-preview [:maybe link-preview]
    :supabase-init [:enum :loading :ready :failed]
    :appear [:map-of :any :boolean]
    :init (c/optional-map {:loader [:map-of c/id init-entry] :scope [:map-of c/id init-entry]})
    :menu :boolean :is-personal :boolean :theme-force-dark :boolean
    :user [:maybe c/id] :active-user [:maybe store/profile] :user-section [:sequential :keyword]
    :experiments [:maybe :keyword] :fragment [:maybe :string] :soft-path [:maybe :string]
    :tab-visible :boolean :cookies-allowed :boolean :login-show-password :boolean
    :is-loading :map :booted [:map-of [:or c/id c/path] :boolean] :motion-seen [:map-of :any :boolean]
    :form-field form-fields :scroll-position [:map-of [:maybe c/named] number?]
    :scroll (c/optional-map {:at-bottom :boolean :past-top :boolean :block :boolean})
    :settings (c/optional-map {:panel-open :boolean})
    :document (c/optional-map {:title [:maybe :string]})
    :content (c/optional-map {:status [:enum :idle :loading :ready :error]})
    :search (c/optional-map {:open? :boolean :results-open? :boolean})
    :chat (c/optional-map {:visible :boolean})
    :cv (c/optional-map {:visited :boolean})
    :docs (c/optional-map {:current-page [:maybe :string] :previous-page [:maybe :string]})
    :github (c/optional-map {:pages-fetched [:sequential :int] :commits-fetched c/strings})
    :strava (c/optional-map {:activity-expanded [:maybe :int]})
    :ssr (c/optional-map {:hydrating? :boolean :dates [:map-of number? :string]})
    :window (c/optional-map {:fullscreen? :boolean}) :hidden flags
    :browser-nav (c/optional-map {:got-nav :boolean :nav-type [:maybe [:or c/named :int]] :referrer [:maybe :string]})
    :contact-form (c/optional-map {:show? :boolean :sent? :boolean :closing? :boolean :response :any})
    :carousel [:map-of c/id (c/optional-map {:index :int :direction [:or :keyword :string]})]
    :supabase-writes [:map-of :any [:or :boolean [:map-of :any :boolean]]]
    :debug (c/optional-map {:layers :boolean :divs :boolean :hydration-token :uuid})
    :css-var [:map-of c/named [:or number? :string]]}))
(def options
  (c/optional-map
   {:auto-save-vars :boolean
    :transition (c/optional-map {:time c/milliseconds :style :keyword})
    :theme (c/optional-map {:dark-mode :boolean :colorscheme :string})
    :github (c/optional-map {:user :string :repo :string})
    :user (c/optional-map {:auto-open? :boolean})
    :hud (c/optional-map {:timeout c/milliseconds :level :keyword})
    :dev-console (c/optional-map {:recording? :boolean :hydration-highlight? :boolean :limit c/positive})
    :supabase (c/optional-map {:url [:maybe :string] :anon-key [:maybe :string]
                              :trusted-author-ids [:maybe [:sequential c/id]]})}))
(def route
  [:maybe (c/optional-map {:path :string :template :string :data :map
                           :path-params :map :query-params [:maybe c/query-params] :parameters :map
                           :controllers [:maybe [:sequential :map]]})])
(def report [:map [:contract [:or :keyword :string]] [:issues [:sequential [:map [:path [:vector :any]] [:message :string]]]]])
(def diagnostics
  (c/optional-map {:messages :map :unhandled [:or [:set :any] [:sequential :any]]
                   :validation [:vector {:max 20} report]}))
(def scoped-state [:map-of [:or :string :keyword] [:map-of :any :map]])
