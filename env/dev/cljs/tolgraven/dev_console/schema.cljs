(ns tolgraven.dev-console.schema
  "Bounded inspector metadata. Inspected payloads remain arbitrary EDN."
  (:require [tolgraven.schema.common :as c]))

(def component-id [:tuple :string :string])
(def queries [:vector {:max 100} :any])
(def reason
  (c/optional-map
    {:cause [:enum :mount :capture-start :arguments :subscription-results
             :unclassified :descendant-or-profiler-update]
     :queries queries}))
(def instance
  (c/optional-map
    {:instance :string
     :component component-id
     :parent [:maybe :string]
     :parent-component [:maybe component-id]
     :path [:maybe c/path]
     :page [:maybe :string]
     :key :any
     :native? :boolean
     :depends [:maybe [:sequential :map]]
     :queries queries
     :source [:maybe :map]}))
(def rect
  (c/optional-map (zipmap [:x :y :top :left :bottom :right :width :height] (repeat number?))))
(def source
  (c/optional-map
    {:component [:maybe :string]
     :element [:maybe :string]
     :inspector? :boolean
     :before [:maybe rect]
     :after [:maybe rect]}))
(def record
  (c/optional-map
    {:kind [:enum :trace :epoch :layout :render :view]
     :start c/milliseconds
     :end c/milliseconds
     :duration c/milliseconds
     :base-duration c/milliseconds
     :commit c/milliseconds
     :phase [:enum "mount" "update" "nested-update"]
     :component component-id
     :instance :string
     :op-type :keyword
     :event c/event
     :event/original c/event
     :interceptors :any
     :effects [:maybe :map]
     :tags :map
     :value number?
     :scope [:enum :unknown :inspector :mixed :page]
     :path [:maybe :string]
     :user-input? :boolean
     :last-input [:maybe c/milliseconds]
     :since-input [:maybe number?]
     :sources [:sequential source]
     :source [:maybe :map]
     :reasons [:vector {:max 100} reason]}))
(def state
  (c/optional-map
    {:active [:maybe [:map-of :string instance]]
     :records [:vector {:max 2000} record]
     :selected-instance [:maybe :string]
     :selected-record [:maybe instance]
     :open? :boolean
     :picking? :boolean
     :flashes [:map-of :string :int]
     :flash-token :int}))
(def options
  (c/optional-map
    {:recording? :boolean
     :page-capture? :boolean
     :event-flash? :boolean
     :hydration-highlight? :boolean
     :limit c/positive}))
(def debug-state
  (c/optional-map {:layers :boolean, :divs :boolean, :hydration-token :uuid}))
