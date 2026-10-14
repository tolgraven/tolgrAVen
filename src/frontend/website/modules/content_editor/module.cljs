(ns website.modules.content-editor.module
  (:require [website.modules.content-editor.views :as views]
            [website.modules.content-editor.schema :as schema]))

(def spec
  {:bundle/depends-on #{:main :user}
   :site/owner :user
   :ssr-styles :deferred
   :db-schema {[:state :content-editor] schema/state}
   :view {:controls #'views/<controls>
          :toolbar #'views/<toolbar>}})
