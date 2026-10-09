(ns tolgraven.legacy-debug
  "Optional development preload for the legacy re-frame inspectors."
  (:require [day8.re-frame-10x :as r10x]
            [day8.re-frame-10x.preload]
            [re-frisk.core :as re-frisk]))

;; Keep the 10x panel hidden initially for private windows and mobile devices.
(r10x/show-panel! false)
(re-frisk/enable {:hidden true :ext_height 1000 :ext_width 1200})
;; Remote inspection remains available when explicitly needed:
;; (re-frisk/enable-re-frisk-remote!)
;; Event capture can be disabled with {:events? false} in enable's options.
