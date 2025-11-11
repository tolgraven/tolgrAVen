(ns tolgraven.scroll
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    [tolgraven.util :as util]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))

(def debug (when ^boolean goog.DEBUG rf/debug))

(rf/reg-event-fx :scroll/by
 (fn [_ [_ value & [in-elem-id]]] ; rem (should handle % too tho?), id of container..
   {:scroll/by [value in-elem-id]}))
(rf/reg-fx :scroll/by
 (fn [[value & [in-elem-id]]]
   (util/scroll-by value in-elem-id)))

(rf/reg-event-fx :scroll/to
 (fn [db [_ id delay-ms]]
   (if delay-ms
     {:dispatch-later {:ms delay-ms
                       :dispatch [:scroll/to id]}}
     (when-not (get-in db [:state :scroll :block])
       {:scroll/to id}))))
(rf/reg-fx :scroll/to
 (fn [id]
   (util/scroll-to id)))

(rf/reg-event-fx :scroll/px
 (fn [db [_ px delay-ms]]
   (if delay-ms
     {:dispatch-later {:ms delay-ms
                       :dispatch [:scroll/px px]}}
     (when-not (get-in db [:state :scroll :block])
       {:scroll/px px}))))
(rf/reg-fx :scroll/px
 (fn [px]
   (util/scroll-to-px px)))

(rf/reg-event-fx :scroll/on-navigate [debug]
  (fn [{:keys [db]} [_ path nav-count]]        ; TODO !! on iphone (also mac safari?) cancel transition on browser nav! fugly
    (let [first-nav? (zero? nav-count)
          browser-nav? (get-in db [:state :browser-nav :got-nav])
          restore? (or first-nav? browser-nav?)
          saved-pos (get-in db [:state :scroll-position path])]
      (merge
       {:dispatch-n [[:scroll/and-block (if restore? ; used back/fwd, since clicking a link should equal there or top...
                                          saved-pos
                                          "main")] ; should only scroll to main if in-page nav without browser nav. Currently sometimes does main anyways
                     [:hide-header-footer false false]
                     (when-not restore?
                       [:scroll/past-top false])]} ; ensure little square in corner goes away since scroll to "main" = side line not extending up to make it luk gud
       (when browser-nav?
         {:dispatch-later {:ms 300 ; should ofc rather queue up to fire on full page (size) load... something-Observer I guess
                           :dispatch [:state [:browser-nav :got-nav] false]} }))))) ; waiting because checks in main-page

(rf/reg-event-fx :scroll/px-dev
 (fn [db [_ delay-ms k px]]
   (let [px' (get px k px)]
     (if delay-ms
       {:dispatch-later {:ms delay-ms
                         :dispatch [:scroll/px px']}}
       (when-not (get-in db [:state :scroll :block])
         {:scroll/px px'})))))

(rf/reg-event-fx :scroll/save-position-dev
  (fn [{:keys [db]} [_]]
    {:dispatch [:ls/store-val [:scroll-position]
                  (.-scrollY js/window)]}))

(rf/reg-event-fx :scroll/restore-position-dev
  (fn [{:keys [_]} [_ delay-ms]]
    {:dispatch [:ls/get-path-as-event
                [:scroll-position :dev-current]
                [:scroll/px delay-ms]]}))

(rf/reg-event-fx :scroll/to-top-and-arm-restore ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ path saved-pos]]
    {;:db (assoc-in db [:state :scrolling-to-top] true)
     :dispatch-n [[:scroll/to "linktotop"]
                  ; [:diag-could-work-in-context-of-long-articles-etc] ;but too annoying on most all navs
                  ;; diag first time it happens to point out new button next to "to top" button
                  ;; which will show when available
                  ;; beauty is with ls it'll work across reloads etc as well
                  ;; could also expose as pref whether to force old more (crappy) appy behavior
                  ]
     :dispatch-later {:ms 50
                      :dispatch [:scroll/set-block true]}}))

(rf/reg-event-fx :scroll/and-block ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ px-or-elem-id]]
    {:dispatch (if (number? px-or-elem-id)
                 [:scroll/px px-or-elem-id]
                 [:scroll/to px-or-elem-id])
     :dispatch-later {:ms 50
                      :dispatch [:scroll/set-block true]}}))

(rf/reg-event-fx :scroll/set-block
  (fn [{:keys [db]} [_ is?]]
    (merge
     {:db (assoc-in db [:state :scroll :block] is?)}
     (when is?
       {:dispatch-later {:ms 1000
                         :dispatch [:scroll/set-block false]}}))))


(rf/reg-event-fx :scroll/direction    [(rf/inject-cofx :css-var [:header-with-menu-height])
                                       (rf/inject-cofx :css-var [:header-height])
                                       (rf/inject-cofx :css-var [:footer-height])
                                       (rf/inject-cofx :css-var [:space-top])
                                       (rf/inject-cofx :css-var [:space-lg])
                                       (rf/inject-cofx :css-var [:footer-height-current])]
 (fn [{:keys [db css-var]} [_ direction position height at-top? at-bottom?]]
   (let [header-height (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var))
         past-top? (not at-top?) ; + space-lg above main. but header + 2x space-top seems sufficient...
         hidden? (get-in db [:state :hidden :header])
         footer-hidden? (get-in db [:state :hidden :footer])] ; will jump page so...
     {:dispatch-n [(cond (or (and (or hidden? footer-hidden?)
                                  (= direction :up))
                             (< position 50))
                         [:hide-header-footer false false]

                         (and (or (not footer-hidden?) ; not checking for already hidden leads to spam
                                  hidden?)
                              at-bottom?)
                         [:hide-header-footer false true] ; don't unhide footer at bottom...

                         (and (not hidden?)
                              (= direction :down)
                              past-top?
                              (not at-bottom?)
                              (not (get-in db [:state :menu])))
                         [:hide-header-footer true true]) ; hide header and footer
                   
                  (if (and at-bottom? (not (get-in db [:state :scroll :at-bottom])))
                    [:scroll/at-bottom true]
                    (when (and (not at-bottom?) (get-in db [:state :scroll :at-bottom]))
                      [:scroll/at-bottom false]))

                  (if (and past-top? (not (get-in db [:state :scroll :past-top])))
                    [:scroll/past-top true]
                    (when (and (not past-top?) (get-in db [:state :scroll :past-top]))
                      [:scroll/past-top false]))]})))

(rf/reg-event-fx :scroll/past-top
 (fn [{:keys [db]} [_ past-top?]]
   {:db (assoc-in db [:state :scroll :past-top] past-top?) }))

(rf/reg-event-fx :scroll/at-bottom    [(rf/inject-cofx :css-var [:footer-height])
                                       (rf/inject-cofx :css-var [:footer-height-full])]
 (fn [{:keys [db css-var]} [_ bottom?]]
   (merge
    {:db (assoc-in db [:state :scroll :at-bottom] bottom?)
     :dispatch-n [[:->css-var! "footer-height-current"
                   (if bottom?
                     "0rem"
                     (if (get-in db [:state :hidden :footer])
                       "calc(2 * var(--line-width))"
                       (:footer-height css-var)))]]})))

