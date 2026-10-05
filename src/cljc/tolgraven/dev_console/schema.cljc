(ns tolgraven.dev-console.schema
  "Bounded inspector metadata. Inspected payloads remain arbitrary EDN."
  (:require [tolgraven.schema.common :as c]))

(def component-id [:tuple :string :string])
(def instance
  (c/optional-map {:instance :string :component component-id :parent [:maybe :string]
                   :parent-component [:maybe component-id] :path [:maybe c/path]
                   :page [:maybe :string] :key :any :depends [:maybe [:sequential :map]]}))
(def rect
  (c/optional-map (zipmap [:x :y :top :left :bottom :right :width :height] (repeat number?))))
(def source
  (c/optional-map {:component [:maybe :string] :element [:maybe :string]
                   :inspector? :boolean :before [:maybe rect] :after [:maybe rect]}))
(def record
  (c/optional-map {:kind [:enum :trace :epoch :layout :render] :start c/milliseconds
                   :end c/milliseconds :duration c/milliseconds :base-duration c/milliseconds
                   :commit c/milliseconds :phase [:enum "mount" "update" "nested-update"]
                   :component component-id :instance :string :op-type :keyword
                   :event c/event :event/original c/event :interceptors :any :effects [:maybe :map]
                   :tags :map :value number? :scope [:enum :unknown :inspector :mixed :page]
                   :path [:maybe :string] :user-input? :boolean
                   :last-input [:maybe c/milliseconds] :since-input [:maybe number?]
                   :sources [:sequential source]}))
(def state (c/optional-map {:active [:maybe [:map-of :string instance]]
                            :records [:vector {:max 2000} record]}))
