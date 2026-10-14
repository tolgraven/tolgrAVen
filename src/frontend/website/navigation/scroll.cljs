(ns website.navigation.scroll
  (:require
    [website.react :as r]

    [website.navigation.transition :as page-transition]
    [website.navigation.component-routes :as component-routes]
    [clojure.string :as string]
    [website.util :as util]))

(def debug (when ^boolean goog.DEBUG r/debug))

(r/reg-event-fx :scroll/by
 (fn [_ [_ value & [in-elem-id]]] ; rem (should handle % too tho?), id of container..
   {:scroll/by [value in-elem-id]}))
(r/reg-fx :scroll/by
 (fn [[value & [in-elem-id]]]
   (util/scroll-by value in-elem-id)))

(r/reg-event-fx :scroll/to
 (fn [db [_ id delay-ms]]
   (if delay-ms
     {:dispatch-later {:ms delay-ms
                       :dispatch [:scroll/to id]}}
     (when-not (get-in db [:state :scroll :block])
       {:scroll/to id}))))
(r/reg-fx :scroll/to
 (fn [id]
   (util/scroll-to id)))

(r/reg-event-fx :scroll/component-target
  {:args [:tuple :string :string]}
  (fn [{:keys [db]} [_ id path]]
    (when (and (= path (get-in db [:common/route :path]))
               (not= {:path path, :scroll? false} (get-in db [:state :scroll :component-focus])))
      {:scroll/component-target id})))

(r/reg-fx :scroll/component-target
  (fn [id]
    ;; A committed component destination supersedes the page's earlier anchor
    ;; restoration; keep the normal layout-settlement and user-cancel behavior.
    (page-transition/restore-anchor! id)))

(r/reg-event-fx :scroll/px
 (fn [db [_ px delay-ms]]
   (if delay-ms
     {:dispatch-later {:ms delay-ms
                       :dispatch [:scroll/px px]}}
     (when-not (get-in db [:state :scroll :block])
       {:scroll/px px}))))
(r/reg-fx :scroll/px
 (fn [px]
   (util/scroll-to-px px)))

(r/reg-event-fx :scroll/on-navigate [debug]
  (fn [{:keys [db]} [_ path nav-count completion]]
    (let [first-nav? (zero? nav-count)
          browser-nav? (get-in db [:state :browser-nav :got-nav])
          component-only? (and (not first-nav?) (not browser-nav?)
                               (get-in db [:common/route :data :component-route])
                               (component-routes/same-page? (:common/route-last db) (:common/route db)))
          restore? (or first-nav? browser-nav?)
          saved-pos (get-in db [:state :scroll-position path])]
      {:db (cond-> db browser-nav? (assoc-in [:state :browser-nav :got-nav] false))
       :dispatch-n [(when-not component-only? [:hide-header-footer false false])
                    (when-not (or restore? component-only?) [:scroll/past-top false])
                    [:page/ready (when (and (not component-only?) (not first-nav?)
                                           (or (not restore?) (number? saved-pos)))
                                   (if restore? saved-pos "main"))
                     completion]]})))


(r/reg-event-fx :scroll/save-history [(r/inject-cofx :scroll-position)]
  (fn [{:keys [db scroll-position]} _]
    (when-let [path (get-in db [:common/route :path])]
      {:db (assoc-in db [:state :scroll-position path] scroll-position)})))

(r/reg-event-fx :scroll/restore-history
  (fn [{:keys [db]} [_ path]]
    (when-let [position (get-in db [:state :scroll-position (or path (get-in db [:common/route :path]))])]
      {:scroll/restore-position position})))

(r/reg-fx :scroll/restore-position
  (fn [position]
    ;; An explicitly client-restored document starts observing before its
    ;; content mounts. Layout growth, not hydration timing, makes it scrollable.
    (page-transition/restore-position! position)))

(r/reg-event-fx :scroll/save-position-dev
  (fn [{:keys [db]} [_]]
    {:dispatch [:ls/store-val [:scroll-position :dev-current]
                  (.-scrollY js/window)]}))

(r/reg-event-fx :scroll/restore-position-dev
  (fn [{:keys [_]} [_ delay-ms]]
    {:dispatch-later {:ms delay-ms
                      :dispatch [:ls/get-path-as-event
                                 [:scroll-position :dev-current]
                                 [:scroll/px]]}}))

(r/reg-event-fx :scroll/to-top-and-arm-restore ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
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

(r/reg-event-fx :scroll/and-block ; when follow links etc want to get to top of new page. but if have visited page earlier, offer to restore latest view pos. ACE!!!
  (fn [{:keys [db]} [_ px-or-elem-id]]
    {:dispatch (if (number? px-or-elem-id)
                 [:scroll/px px-or-elem-id]
                 [:scroll/to px-or-elem-id])
     :dispatch-later {:ms 50
                      :dispatch [:scroll/set-block true]}}))

(r/reg-event-fx :scroll/set-block
  (fn [{:keys [db]} [_ is?]]
    (merge
     {:db (assoc-in db [:state :scroll :block] is?)}
     (when is?
       {:dispatch-later {:ms 1000
                         :dispatch [:scroll/set-block false]}}))))


(r/reg-event-fx :scroll/direction    [(r/inject-cofx :css-var [:header-with-menu-height])
                                       (r/inject-cofx :css-var [:header-height])
                                       (r/inject-cofx :css-var [:footer-height])
                                       (r/inject-cofx :css-var [:space-top])
                                       (r/inject-cofx :css-var [:space-lg])
                                       (r/inject-cofx :css-var [:footer-height-current])
                                       (r/inject-cofx :scroll/restoring?)]
 (fn [{:keys [db css-var] restoring? :scroll/restoring?} [_ direction position height at-top? at-bottom?]]
   (let [header-height (if (get-in db [:state :menu])
                            (:header-with-menu-height css-var)
                            (:header-height css-var))
         past-top? (not at-top?) ; + space-lg above main. but header + 2x space-top seems sufficient...
         hidden? (get-in db [:state :hidden :header])
         footer-hidden? (get-in db [:state :hidden :footer])] ; will jump page so...
     {:dispatch-n [(when-not restoring?
                      (cond (or (and (or hidden? footer-hidden?)
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
                         [:hide-header-footer true true])) ; hide header and footer
                   
                  (if (and at-bottom? (not (get-in db [:state :scroll :at-bottom])))
                    [:scroll/at-bottom true]
                    (when (and (not at-bottom?) (get-in db [:state :scroll :at-bottom]))
                      [:scroll/at-bottom false]))

                  (if (and past-top? (not (get-in db [:state :scroll :past-top])))
                    [:scroll/past-top true]
                    (when (and (not past-top?) (get-in db [:state :scroll :past-top]))
                      [:scroll/past-top false]))]})))

(r/reg-event-fx :scroll/past-top
 (fn [{:keys [db]} [_ past-top?]]
   {:db (assoc-in db [:state :scroll :past-top] past-top?) }))

(r/reg-event-fx :scroll/at-bottom    [(r/inject-cofx :css-var [:footer-height])
                                       (r/inject-cofx :css-var [:footer-height-full])]
 (fn [{:keys [db css-var]} [_ bottom?]]
   (merge
    {:db (assoc-in db [:state :scroll :at-bottom] bottom?)
     :dispatch-n [[:->css-var! "footer-height-current"
                   (if bottom?
                     "0rem"
                     (if (get-in db [:state :hidden :footer])
                       "calc(2 * var(--line-width))"
                       (:footer-height css-var)))]]})))
