(ns tolgraven.content.schema
  "Public CMS sections, after semantic normalization. Editor-owned optional
   fields may be absent; present values must have the shape consumed by views."
  (:require [tolgraven.schema.common :as c]))

(def media (c/optional-map {:src :string :alt :string :poster :string :title :string
                           :playsInline :boolean :autoPlay :boolean :muted :boolean
                           :loop :boolean :controls :boolean :width [:or number? :string]
                           :height [:or number? :string]
                           :sizes :string
                           :loading [:enum "lazy" "eager"]
                           :decoding [:enum "async" "sync" "auto"]
                           :fetchPriority [:enum "high" "low" "auto"]}))
(def heading (c/optional-map {:title :string :caption :string :target [:maybe c/named]
                             :bg media :tint :string}))
(def link (c/optional-map {:id c/id :name :string :href :string :info :string :icon :string}))
(def menu-item [:and vector? [:cat :string :string [:? [:maybe c/named]]]])
(def timeline-item
  (c/optional-map {:from [:or number? :string] :to [:maybe [:or number? :string]]
                   :what :string :where :string :how c/strings :logo :string :color :string}))
(def timeline-column
  (c/optional-map {:category c/named :icon :string :height-ratio number?
                   :things [:sequential timeline-item]}))
(def cv-body
  (c/optional-map {:intro :string :caption :string :background-color-indexes [:sequential :int]
                   :timeline [:sequential timeline-column] :skills [:map-of c/named c/strings]}))
(def footer-item
  (c/optional-map {:id c/id :email :string :title :string :text c/strings :logo media
                   :links [:sequential link] :images [:sequential media]}))
(def sections
  {:document (c/optional-map {:title :string :description :string :base-url :string :author :string :email :string})
   :header (c/optional-map {:text [:tuple :string c/strings] :text-personal [:tuple :string c/strings]
                            :menu [:map-of c/named [:sequential menu-item]]})
   :intro (c/optional-map {:title :string :text :string :logo-bg :string :bg [:sequential media]
                           :buttons [:sequential [:tuple :string [:or :string c/event]]]})
   :services (c/optional-map {:caption :string :bg media
                              :categories [:sequential [:tuple :string :string c/strings]]})
   :moneyshot heading
   :story (c/optional-map {:heading heading :title :string :text :string
                           :images [:sequential [:and vector? [:cat :string media [:? :string] [:? :string]]]]})
   :strava (c/optional-map {:story :string :background :string :profile-url :string
                            :gear-info [:map-of c/named (c/optional-map {:img :string :desc :string})]})
   :interlude [:vector heading]
   :cv (c/optional-map {:heading heading :title :string :caption :string :cv cv-body})
   :soundcloud (c/optional-map {:url :string :artist :string :tunes c/strings})
   :gallery [:vector media]
   :blog (c/optional-map {:heading heading})
   :docs (c/optional-map {:heading heading})
   :common (c/optional-map {:banner-heading heading :user-avatar-fallback :string})
   :footer [:vector footer-item]
   :post-footer [:vector footer-item]})
(def content (into [:map {:closed true}]
                   (map (fn [[key schema]] [key {:optional true} schema])) sections))
(def bundle [:map [:version [:= 1]] [:content content]
             [:deferred? {:optional true} :boolean]])

(def state (c/optional-map {:status [:enum :idle :loading :ready :error]}))
