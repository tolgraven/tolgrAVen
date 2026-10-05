(ns tolgraven.schema.state
  "Application state assembly. Feature contracts live with their owners; native
   events, refs and exceptions remain intentionally opaque."
  (:require [tolgraven.schema.common :as c]
            [tolgraven.blog.schema :as blog]
            [tolgraven.chat.schema :as chat]
            [tolgraven.content.schema :as content]
            [tolgraven.cv.schema :as cv]
            [tolgraven.dev-console.schema :as dev-console]
            [tolgraven.docs.schema :as docs]
            [tolgraven.github.schema :as github]
            [tolgraven.gpt.schema :as gpt]
            [tolgraven.hud.schema :as hud]
            [tolgraven.link-preview.schema :as link-preview]
            [tolgraven.search.schema :as search]
            [tolgraven.settings.schema :as settings]
            [tolgraven.ssr.schema :as ssr]
            [tolgraven.strava.schema :as strava]
            [tolgraven.supabase.schema :as supabase]
            [tolgraven.theme.schema :as theme]
            [tolgraven.ui.schema :as ui]
            [tolgraven.user.schema :as user]
            [tolgraven.views-common.schema :as views-common]
            [tolgraven.window.schema :as window]))

(def flags [:map-of c/id :boolean])
(def form-fields
  (c/optional-map {:chat chat/form-field
                   :gpt-thread gpt/form-fields
                   :login user/login-fields
                   :change-password user/password-fields
                   :contact views-common/contact-fields
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
    :window window/state
    :hidden flags
    :browser-nav (c/optional-map {:got-nav :boolean
                                  :nav-type [:maybe [:or c/named :int]]
                                  :referrer [:maybe :string]})
    :contact-form views-common/contact-state
    :carousel ui/carousel-state
    :supabase-writes supabase/writes
    :debug dev-console/debug-state
    :css-var [:map-of c/named [:or number? :string]]}))
(def options
  (c/optional-map
   {:auto-save-vars :boolean
    :transition ui/transition-options
    :theme theme/options
    :github github/options
    :user user/options
    :hud hud/options
    :dev-console dev-console/options
    :supabase supabase/options}))
(def route
  [:maybe (c/optional-map {:path :string
                           :template :string
                           :data :map
                           :path-params :map
                           :query-params [:maybe c/query-params]
                           :parameters :map
                           :controllers [:maybe [:sequential :map]]})])
(def report [:map [:contract [:or :keyword :string]] [:issues [:sequential [:map [:path [:vector :any]] [:message :string]]]]])
(def diagnostics
  (c/optional-map {:messages :map
                   :unhandled [:or [:set :any] [:sequential :any]]
                   :validation [:vector {:max 20} report]}))
(def scoped-state [:map-of [:or :string :keyword] [:map-of :any :map]])
