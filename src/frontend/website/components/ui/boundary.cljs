(ns website.components.ui.boundary
  (:require [website.component.registry]
            [website.macros :as m :include-macros true]
            [website.component :as component]))


;; GENERAL WRAPPER COMPONENT
;; * Error boundary
;; * Appear / Seen
;; * Lazy load / init ref wrapping
;; * Unmount animation
;;
;; Maybe destructure [& args] into [spec & components]?
;; defcomp macro
;;
;; spec is:
;; :props -> goes in div
;; :opts  -> component specific specs (easier than select-keys on zillions)
;; :with  -> {:appear :seen :lazy-load :<loading> :error-boundary} etc, defaults to be worked out, nil means default, false off, map custom?
;; :init  -> to wrap :ref in, or just wrap whatever we put in :props :ref?
;; :exit  -> any cleanup
;; :css   -> if go with css in js but nah dunno, but if modular scss how ensure css gets loaded here?
;;
;; possibly more structured/dataish :init and :exit, for setup of websocket channels and such
;; that will always be similar enough. it's that or sub abuse, dunno.
;; also just init event to dispatch
;; also setup event for when component module is loaded, ensure other data fetch goes parallel

(m/defc <safe>
  "Group error diagnostics by category, with the caller's recovery key."
  [category form & [reset-key]]
  [component/<boundary> {:ns-name "website.components.ui.boundary" :component-name (name category)
                         :reset-key reset-key} form])
