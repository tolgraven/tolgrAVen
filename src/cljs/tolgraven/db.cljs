(ns tolgraven.db
  (:require [reagent.core :as r]
            [tolgraven.react :as rf]
            [cljs.reader]))


;; NEW ARCH STUFF:
;; * pt-style per-page general subs/puts (global, page, view etc)

; (s/defschema $Scope
;   "Schema for supported DB path scopes."
;   (s/enum :global :active-view :page :root :local-storage :session-storage))
(def state-scopes [:global :view :page :local-storage :session-storage])
;; TODO possibly hook-in a :remote as well, if logged in, per-user auto-store but on server
;; use same debounce methods as local/session storage etc.

;; Also might want easy "shortcuts" to persist/restore entire for example :page scope to local storage
;; or fetch and populate :page scope from remote or ls


(def data ; default db. Needs to be cleaned out of content already haha.
  {:state {:menu false
           :user-section [:closed]
           :is-loading {}
           :theme-force-dark true
           :is-personal false
           :experiments :parallax
           :debug {:layers false
                   :divs false}
           ; :transition :out ;later when proper boot sequence, trigger in on load complete
           }
   :routes {:home "/"
            :about "/about"
            :docs "docs"
            :blog "/blog"
            :log "log"}
   :content {} ; populated from the backend content bundle before mounting

   :options {:auto-save-vars true
             :transition {:time 500 :style :slide} ; etc
             :blog {:posts-per-page 3} ; XXX should go in query-params no
             :supabase {:url nil
                        :anon-key nil}
             :theme {:dark-mode true
                     :colorscheme "default"}
             :github {:user "tolgraven"
                      :repo "tolgraven"}
             :hud {:timeout 30 :level :info}}
   :cookie-notice {:a :map}})


(rf/reg-event-db :init/app-db
  (fn [db _]
    data))
