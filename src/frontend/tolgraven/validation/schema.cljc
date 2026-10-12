(ns tolgraven.validation.schema
  "Application state assembly. Feature contracts live with their owners; native
   events, refs and exceptions remain intentionally opaque."
  #?(:cljs (:require [tolgraven.schema.common :as c]
            [tolgraven.component.font :as font]
            #?(:module/blog [tolgraven.modules.blog.schema :as blog])
            #?(:module/chat [tolgraven.modules.chat.schema :as chat])
            [tolgraven.content.schema :as content]
            #?(:module/cv [tolgraven.modules.cv.schema :as cv])
            #?(:module/contact [tolgraven.modules.contact.schema :as contact])
            #?(:module/settings [tolgraven.modules.settings.schema :as settings])
            [tolgraven.diagnostics.schema :as dev-console]
            #?(:module/docs [tolgraven.modules.docs.schema :as docs])
            #?(:module/github [tolgraven.modules.github.schema :as github])
            #?(:module/gpt [tolgraven.modules.gpt.schema :as gpt])
            #?(:module/instagram [tolgraven.modules.instagram.schema :as instagram])
            #?(:module/link-preview [tolgraven.modules.link-preview.schema :as link-preview])
            #?(:module/search [tolgraven.modules.search.schema :as search])
            [tolgraven.ssr.schema :as ssr]
            #?(:module/strava [tolgraven.modules.strava.schema :as strava])
            [tolgraven.supabase.schema :as supabase]
            [tolgraven.navigation.schema :as navigation]
            #?(:module/user [tolgraven.modules.user.schema :as user]) )))

#?(:cljs
   (do
(defn- optional-map [fields]
  (c/optional-map (into {} (remove (comp nil? val)) fields)))

(def flags [:map-of c/id :boolean])
(def form-fields
  (optional-map {:chat #?(:module/chat chat/form-field :default nil)
                   :gpt-thread #?(:module/gpt gpt/form-fields :default nil)
                   :login #?(:module/user user/login-fields :default nil)
                   :change-password #?(:module/user user/password-fields :default nil)
                   :contact #?(:module/contact contact/fields :default nil)
                   :post-blog #?(:module/blog blog/post-fields :default nil)
                   :write-comment #?(:feature/blog-comments blog/comment-fields :default nil)}))
(def link-candidate #?(:module/link-preview link-preview/link-candidate :default nil))
(def link-preview #?(:module/link-preview link-preview/state :default nil))
(def init-entry (optional-map {:inited? :boolean, :args [:?sequential :any]}))
(def state
  (optional-map
   {:link-preview #?(:module/link-preview [:maybe link-preview] :default nil)
    :supabase-init supabase/init-status
    :appear [:map-of :any :boolean]
    :init (optional-map {:loader [:map-of c/id init-entry], :scope [:map-of c/id init-entry]})
    :menu :boolean
    :is-personal :boolean
    :theme-force-dark :boolean
    :user #?(:module/user user/user-id :default nil)
    :active-user #?(:module/user user/active-user :default nil)
    :user-section #?(:module/user user/section :default nil)
    :experiments :?keyword
    :fragment :?string
    :soft-path :?string
    :tab-visible :boolean
    :cookies-allowed :boolean
    :login-show-password :boolean
    :is-loading :map
    :booted [:map-of [:or c/id c/path] :boolean]
    :motion-seen [:map-of :any :boolean]
    :form-field form-fields
    :scroll-position [:map-of [:maybe c/named] number?]
    :scroll (optional-map {:at-bottom :boolean, :past-top :boolean, :block :boolean})
:settings #?(:module/settings settings/state :default nil)
    :document (optional-map {:title :?string})
    :content content/state
    :search #?(:module/search search/state :default nil)
    :chat #?(:module/chat chat/state :default nil)
    :cv #?(:module/cv cv/state :default nil)
    :docs #?(:module/docs docs/state :default nil)
    :github #?(:module/github github/state :default nil)
    :strava #?(:module/strava strava/state :default nil)
    :ssr ssr/state
    :window (optional-map {:fullscreen? :boolean})
    :hidden flags
    :browser-nav (optional-map {:got-nav :boolean
                                  :nav-type [:?or c/named :int]
                                  :referrer :?string})
    :contact-form #?(:module/contact contact/state :default nil)
    :carousel [:map-of c/id (optional-map {:index :int
                                           :direction [:or :keyword :string]})]
    :supabase-writes supabase/writes
    :debug dev-console/debug-state
    :css-var [:map-of c/named [:or number? :string]]}))
(def options
  (optional-map
   {:auto-save-vars :boolean
    :transition (optional-map {:time c/milliseconds, :style :keyword})
    :theme #?(:module/settings settings/theme :default nil)
    :github #?(:module/github github/options :default nil)
    :user #?(:module/user user/options :default nil)
    :hud (optional-map {:timeout c/milliseconds, :level :keyword})
    :dev-console dev-console/options
    :supabase supabase/options}))
(def report [:map [:contract [:or :keyword :string]] [:issues [:sequential [:map [:path [:vector :any]] [:message :string]]]]])
(def diagnostics
  (optional-map {:messages :map
                   :unhandled [:or [:set :any] [:sequential :any]]
                   :validation [:vector {:max 20} report]}))
(def scoped-state [:map-of [:or :string :keyword] [:map-of :any :map]])

(def sections
  (into {} (remove (comp nil? val))
   (merge #?(:module/blog blog/sections :default nil)
    {[:state] state
     [:options] options
     [:content] (optional-map
                 (merge content/sections
                   {:github #?(:module/github github/content :default nil)
                    :strava #?(:module/strava (vector :merge (:strava content/sections) strava/content) :default nil)
                    :instagram #?(:module/instagram (optional-map {:posts [:map-of c/id instagram/post]
                                                                  :error :any}) :default nil)}))
     [:store] supabase/store
     [:search] #?(:module/search search/results :default nil)
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
     [:page-return] (optional-map {:status [:enum :ready :unavailable :saving]
                                    :url :string
                                    :message :string})
     [:fonts] font/state-schema
     [:loader] (optional-map {:code-ready [:map-of :keyword :boolean]
                              :requested [:map-of :keyword :boolean]
                              :errors :map})})))
))
