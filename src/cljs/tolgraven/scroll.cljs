(ns tolgraven.scroll
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [clojure.string :as string]
    [tolgraven.util :as util]
    [cljs-time.core :as ct]
    [cljs-time.coerce :as ctc]))


(rf/reg-event-fx :scroll/on-navigate ; TODO detect fresh/reload and treat it as browser nav so dont lose position on page...
  (fn [{:keys [db]} [_ path]]        ; TODO !! on iphone (also mac safari?) cancel transition on browser nav! fugly
    (let [browser-nav? (get-in db [:state :browser-nav :got-nav])
          saved-pos (get-in db [:state :scroll-position path])]
      {;:db (update-in db [:state :browser-nav] dissoc :got-nav) ;clear any browser nav flag since we consume it
       :dispatch (if browser-nav? ; used back/fwd, since clicking a link should equal there or top...
                   [:scroll/and-block (or saved-pos "main")] ; guess should check for frag, query etc tho
                   [:scroll/and-block "main"])
       :dispatch-later {:ms 300 ; should ofc rather queue up to fire on full page (size) load... something-Observer I guess
                        :dispatch [:state [:browser-nav :got-nav] false]} }))) ; waiting because checks in main-page


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
     :dispatch-later {:ms 10
                      :dispatch [:scroll/set-block true]}}))

(rf/reg-event-fx :scroll/and-block ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ px-or-elem-id]]
    {:dispatch (if (number? px-or-elem-id)
                 [:scroll/px px-or-elem-id]
                 [:scroll/to px-or-elem-id])
     :dispatch-later {:ms 1
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
 (fn [{:keys [db css-var]} [_ direction position height at-bottom?]]
   (let [header-height (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var))
         past-top? (>= position (+ (util/rem-to-px (:header-height css-var))     ; distance from top to main is header-height + space-top above/below,
                                   (util/rem-to-px (:space-lg css-var))
                                   (* 2 (util/rem-to-px (:space-top css-var))))) ; + space-lg above main. but header + 2x space-top seems sufficient...
         hidden? (get-in db [:state :hidden-header-footer])
         at-bottom? (>= position
                        (- height
                           (.-innerHeight js/window)
                           50
                           (util/rem-to-px (:footer-height-current css-var))))] ; will jump page so...
    {:dispatch-n [(if (or (and hidden?
                               (= direction :up))
                          at-bottom?) ; not checking for already hidden leads to spam but somehow stabilizes...
                   [:hide-header-footer false]
                   (when (and (not hidden?)
                              (= direction :down)
                              past-top?
                              (not (get-in db [:state :menu])))
                     [:hide-header-footer true]))
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
                   (if (and bottom?) ; "is on front page", trickier than first might think due to idiosyncraticlol design choices
                     (:footer-height-full css-var)
                     (if (get-in db [:state :hidden-header-footer])
                       "0rem"
                       (:footer-height css-var)))]]}
   (when bottom?
     {:dispatch-later {:ms 300
                       :dispatch
                       [:scroll/by (js/parseFloat (:footer-height-full css-var))]}}))))

