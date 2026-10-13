(ns website.content.schema
  "Public CMS sections, after semantic normalization. Editor-owned optional
   fields may be absent; present values must have the shape consumed by views."
  (:require [clojure.string :as string]
            [website.schema.common :as c]
            [website.media.schema :as media-schema]
            #?(:clj [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]])))

(def media (c/optional-map {:src :string :alt :string :poster :string :title :string
                           :playsInline :boolean :autoPlay :boolean :muted :boolean
                           :loop :boolean :controls :boolean :width [:or number? :string]
                           :height [:or number? :string]
                           :asset media-schema/asset
                           :renditions media-schema/renditions
                           :placeholder media-schema/placeholder
                           :sizes :string
                           :loading [:enum "lazy" "eager"]
                           :decoding [:enum "async" "sync" "auto"]
                           :fetchPriority [:enum "high" "low" "auto"]}))
(def heading (c/optional-map {:title :string :caption :string :target [:maybe c/named]
                             :bg media :tint :string}))
(def link (c/optional-map {:id c/id :name :string :href :string :info :string :icon :string}))
(def menu-item [:and vector? [:cat :string :string [:? [:maybe c/named]]]])
(def timeline-item
  (c/optional-map {:from [:or number? :string] :to [:?or number? :string]
                   :what :string :where :string :how c/strings :logo :string :color :string}))
(def timeline-column
  (c/optional-map {:category c/named :icon :string :height-ratio number?
                   :things [:sequential timeline-item]}))
(def cv-body
  (c/optional-map {:intro :string :caption :string :background-color-indexes [:sequential :int]
                   :timeline [:sequential timeline-column] :skills [:map-of c/named c/strings]}))
(def code-language
  [:map
   [:value [:re #"^[a-zA-Z0-9_+-]{1,80}$"]]
   [:label [:and [:string {:min 1, :max 120}] [:fn #(and (string? %) (not (string/blank? %)))]]]])
(def code-languages
  [:and [:vector {:max 200} code-language]
   [:fn #(and (vector? %) (= (count %) (count (distinct (map :value %)))))]])
(def footer-item
  (c/optional-map {:id c/id :email :string :title :string :text c/strings :logo media
                   :links [:sequential link] :images [:sequential media]}))
(def sections
  (into {} (remove (comp nil? val))
  {:document (c/optional-map {:title :string :description :string :base-url :string :author :string :email :string})
   :header (c/optional-map {:text [:tuple :string c/strings] :text-personal [:tuple :string c/strings]
                            :menu [:map-of c/named [:sequential menu-item]]})
   :intro (when-enabled :home nil (c/optional-map {:title :string :text :string :logo-bg :string :bg [:sequential media]
                           :buttons [:sequential [:tuple :string [:or :string c/event]]]}))
   :services (when-enabled :home nil (c/optional-map {:caption :string :bg media
                              :categories [:sequential [:tuple :string :string c/strings]]}))
   :moneyshot (when-enabled :home nil heading)
   :story (when-enabled :home nil (c/optional-map {:heading heading :title :string :text :string
                           :images [:sequential [:and vector? [:cat :string media [:? :string] [:? :string]]]]}))
   :strava (when-enabled :strava nil (c/optional-map {:story :string :background :string :profile-url :string
                            :gear-info [:map-of c/named (c/optional-map {:img :string :desc :string})]}))
   :interlude (when-enabled :home nil [:vector heading])
   :cv (when-enabled :cv nil (c/optional-map {:heading heading :title :string :caption :string :cv cv-body}))
   :soundcloud (when-enabled :home nil (c/optional-map {:url :string :artist :string :tunes c/strings}))
   :gallery (when-enabled :home nil [:vector media])
   :blog (when-enabled :blog nil (c/optional-map {:heading heading}))
   :docs (when-enabled :docs nil (c/optional-map {:heading heading}))
   :common (c/optional-map {:banner-heading heading
                            :user-avatar-fallback :string
                            :code-languages code-languages
                            :image-assets [:map-of :string media-schema/asset]})
   :footer [:vector footer-item]
   :post-footer [:vector footer-item]}))
(def content (into [:map {:closed true}]
                   (map (fn [[key schema]]
                          [key {:optional true}
                           (if (#{:document :header :intro :services :moneyshot :story :strava :cv :soundcloud :blog :docs :common} key)
                             [:merge schema [:map [:?image-assets [:map-of :string media-schema/asset]]]]
                             schema)])) sections))
(def bundle [:map [:version [:= 1]] [:content content]
             [:?deferred? :boolean]])

(def state (c/optional-map {:status [:enum :idle :loading :ready :error]}))
