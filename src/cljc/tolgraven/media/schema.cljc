(ns tolgraven.media.schema
  "Portable public media contracts; credentials and durable job internals stay server-side."
  (:require [tolgraven.components.image.schema :as image]))

(def id [:string {:min 1 :max 200}])
(def source [:string {:min 1 :max 4096}])
(def status [:enum "queued" "processing" "ready" "failed"])

(def rendition
  [:map {:closed true}
   [:src source]
   [:width pos-int?]
   [:height pos-int?]
   [:bytes pos-int?]
   [:mime [:enum "image/png" "image/jpeg" "image/webp" "image/avif" "image/svg+xml"]]])

(def raster-rendition
  [:and rendition [:map [:mime [:enum "image/png" "image/jpeg" "image/webp" "image/avif"]]]])

(def vector-rendition
  [:and rendition [:map [:mime [:= "image/svg+xml"]]]])

(def renditions
  [:or
   [:map {:closed true}
    [:original raster-rendition]
    [:webp [:vector {:min 1} raster-rendition]]
    [:avif [:vector {:min 1} raster-rendition]]]
   [:map {:closed true}
    [:original vector-rendition]]])

(def placeholder image/placeholder)

(def asset
  [:map {:closed true}
   [:id id]
   [:src source]
   [:width pos-int?]
   [:height pos-int?]
   [:renditions renditions]
   [:?placeholder placeholder]
   [:?alt :string]])

(def upload-response
  [:and
   [:map {:closed true}
    [:id id]
    [:status status]
    [:?asset asset]
    [:?error :string]]
   [:fn {:error/message "ready media must include its complete asset"}
    #(if (= "ready" (:status %)) (some? (:asset %)) (nil? (:asset %)))]])

(def resolve-query [:map [:src source]])
(def resolved [:map [:?asset asset]])
(def id-path [:map [:id id]])

(def strapi-media
  [:map
   [:id [:or pos-int? id]]
   [:url source]
   [:?mime :string]
   [:?hash :?string]
   [:?alternativeText [:?string {:max 2048}]]
   [:?width [:?int {:min 1}]]
   [:?height [:?int {:min 1}]]])

(def webhook
  [:map
   [:event [:enum "media.create" "media.update" "media.delete"]]
   [:media strapi-media]])

(def settings
  [:map
   [:?enabled :boolean]
   [:?strapi-webhook-token [:string {:min 16}]]
   [:?strapi-media-origins [:vector source]]
   [:?allow-private-cms? :boolean]])

(def library-query
  [:map
   [:?offset [:int {:min 0 :max 100000}]]
   [:?limit [:int {:min 1 :max 50}]]])

(def library
  [:map {:closed true}
   [:assets [:vector asset]]
   [:next-offset :?int]])
