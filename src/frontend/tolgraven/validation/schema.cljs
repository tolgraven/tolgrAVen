(ns tolgraven.validation.schema
  "Application state assembly. Feature contracts live with their owners; native
   events, refs and exceptions remain intentionally opaque."
  (:require [tolgraven.schema.common :as c]
            [tolgraven.modules.blog.schema :as blog]
            [tolgraven.modules.chat.schema :as chat]
            [tolgraven.content.schema :as content]
            [tolgraven.modules.cv.schema :as cv]
            [tolgraven.modules.contact.schema :as contact]
            [tolgraven.modules.settings.schema :as settings]
            [tolgraven.diagnostics.schema :as dev-console]
            [tolgraven.modules.docs.schema :as docs]
            [tolgraven.modules.github.schema :as github]
            [tolgraven.modules.gpt.schema :as gpt]
            [tolgraven.modules.instagram.schema :as instagram]
            [tolgraven.modules.link-preview.schema :as link-preview]
            [tolgraven.modules.search.schema :as search]
            [tolgraven.ssr.schema :as ssr]
            [tolgraven.modules.strava.schema :as strava]
            [tolgraven.supabase.schema :as supabase]
            [tolgraven.navigation.schema :as navigation]
            [tolgraven.modules.user.schema :as user]))

(def flags [:map-of c/id :boolean])
(def form-fields
  (c/optional-map {:chat chat/form-field
                   :gpt-thread gpt/form-fields
                   :login user/login-fields
                   :change-password user/password-fields
                   :contact contact/fields
                   :post-blog blog/post-fields
                   :write-comment blog/comment-fields}))
(def link-candidate link-preview/link-candidate)
(def link-preview link-preview/state)
(def init-entry (c/optional-map {:inited? :boolean, :args [:maybe [:sequential :any]]}))
(def state
  (c/optional-map
   {:link-preview [:maybe link-preview]
    :supabase-init supabase/init-status
    :appear [:map-of :any :boolean]
    :init (c/optional-map {:loader [:map-of c/id init-entry], :scope [:map-of c/id init-entry]})
    :menu :boolean
    :is-personal :boolean
    :theme-force-dark :boolean
    :user user/user-id
    :active-user user/active-user
    :user-section user/section
    :experiments [:maybe :keyword]
    :fragment [:maybe :string]
    :soft-path [:maybe :string]
    :tab-visible :boolean
    :cookies-allowed :boolean
    :login-show-password :boolean
    :is-loading :map
    :booted [:map-of [:or c/id c/path] :boolean]
    :motion-seen [:map-of :any :boolean]
    :form-field form-fields
    :scroll-position [:map-of [:maybe c/named] number?]
    :scroll (c/optional-map {:at-bottom :boolean, :past-top :boolean, :block :boolean})
    :settings settings/state
    :document (c/optional-map {:title [:maybe :string]})
    :content content/state
    :search search/state
    :chat chat/state
    :cv cv/state
    :docs docs/state
    :github github/state
    :strava strava/state
    :ssr ssr/state
    :window (c/optional-map {:fullscreen? :boolean})
    :hidden flags
    :browser-nav (c/optional-map {:got-nav :boolean
                                  :nav-type [:maybe [:or c/named :int]]
                                  :referrer [:maybe :string]})
    :contact-form contact/state
    :carousel [:map-of c/id (c/optional-map {:index :int
                                           :direction [:or :keyword :string]})]
    :supabase-writes supabase/writes
    :debug dev-console/debug-state
    :css-var [:map-of c/named [:or number? :string]]}))
(def options
  (c/optional-map
   {:auto-save-vars :boolean
    :transition (c/optional-map {:time c/milliseconds, :style :keyword})
    :theme settings/theme
    :github github/options
    :user user/options
    :hud (c/optional-map {:timeout c/milliseconds, :level :keyword})
    :dev-console dev-console/options
    :supabase supabase/options}))
(def report [:map [:contract [:or :keyword :string]] [:issues [:sequential [:map [:path [:vector :any]] [:message :string]]]]])
(def diagnostics
  (c/optional-map {:messages :map
                   :unhandled [:or [:set :any] [:sequential :any]]
                   :validation [:vector {:max 20} report]}))
(def scoped-state [:map-of [:or :string :keyword] [:map-of :any :map]])

(def sections
  (merge blog/sections
    {[:state] state
     [:options] options
     [:content] (c/optional-map
                 (merge content/sections
                   {:github github/content
                    :strava (vector :merge (:strava content/sections) strava/content)
                    :instagram (c/optional-map {:posts [:map-of c/id instagram/post]
                                                :error :any})}))
     [:store] supabase/store
     [:search] search/results
     [:docs] [:map-of :string :string]
     [:diagnostics] diagnostics
     [:dev-console] dev-console/state
     [:page/commit] [:maybe navigation/commit]
     [:common/route] navigation/route
     [:common/route-last] navigation/route
     [:component-revisions] [:map-of c/path c/nonnegative]
     ;; Values at these roots are owned by each component/page/module. Their
     ;; :db-schema or :state {:schema ...} adds deeper validation.
     [:component] :map
     [:page] :map
     [:module] :map
     [:global] :map
     [:page-return] (c/optional-map {:status [:enum :ready :unavailable :saving]
                                    :url :string
                                    :message :string})
     [:loader] (c/optional-map {:code-ready [:map-of :keyword :boolean]
                              :requested [:map-of :keyword :boolean]
                              :errors :map})}))
