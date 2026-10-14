(ns website.modules.link-preview.module
  (:require
    [website.modules.link-preview.events]
    [website.modules.link-preview.subs]
    [website.modules.link-preview.schema :as schema]
    [website.modules.link-preview.views :as view]))

(def spec
  {:schema [:map [:link-container schema/container-adapter]]
   :link-container {:observe! view/observe-container!
                    :handle! view/handle-container-event!}
   :server {:requires #{:markdown}
            :routes 'website.modules.link-preview.api/routes}
   :ssr-styles :deferred
   :view {:view #'view/<link-preview>}})
