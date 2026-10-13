(ns website.modules.home.sections
  (:require
    [website.components.icon :as icon]
    [website.component.registry]
    [clojure.string :as string]
    [website.react :as r]
    [website.macros :refer-macros [defc]]

    [website.components.timer :as anim]
    [website.component.restore :as restore]
    [website.components.media :as media]
    [website.components.image :as img]
    [website.modules.home.layout :as layout]
    [website.components.ui.layout :as ui-layout]))

(defn ln->br "Ugh. UGH! Why"
  [text]
  (for [line (string/split-lines text)]
        [:p line]))

(defc <intro-letter> {:features [[:appear "opacity"]]}
  [{:keys [letter] :as spec}]
  [:span letter])

(defn title-delay-ms [title shown]
  (if (zero? shown) 2000
    (+ 25 (* shown 18)
       (if (= (nth title (dec shown)) " ") 500 0)
       (if (= shown (dec (count title))) 600 0))))

(defn title-delays-ms [title]
  (vec (rest (reductions + 0 (map #(title-delay-ms title %) (range (count title)))))))

(defc <intro> [{:keys [title text buttons logo-bg bg] :as spec}]
  :let [document-enter? (restore/document-enter?)
        restored? (and (restore/skip-enter?) (not document-enter?))
        *showing-title (r/atom (if restored? (count title) 0))
        letter-delays (title-delays-ms title)]
  [:section#intro {:data-restored (when restored? true)}
   [media/<bg-logo> logo-bg (get (:image-assets spec) logo-bg)]
   [img/<media-as-bg> (merge (first bg) {:id "top-banner"
                                      :loading "eager"
                                      :fetchPriority "high"})]
   [:div.h1-wrapper.center-content
    [:h1.h-responsive.h-intro
     {:aria-label (when document-enter? (apply str title))}
     (if document-enter?
       ;; All letters exist in SSR. CSS owns the original typing clock before
       ;; JavaScript arrives, and hydration retains these exact nodes/timings.
       [:<>
        [:span.intro-title-dot {:aria-hidden true} "•"]
        (map-indexed
         (fn [i letter]
           ^{:key (str "intro-letter-" i)}
           [:span.intro-title-letter
            {:style {"--intro-letter-delay" (str (nth letter-delays i) "ms")}}
            letter]) title)]
       [:<>
        ;; A keyed timer owns SPA typing, so leaving the page cancels it.
        (when (and (not restored?) (< @*showing-title (count title)))
          ^{:key @*showing-title}
          [anim/<timeout> #(swap! *showing-title inc) (title-delay-ms title @*showing-title)])
        (if (< @*showing-title 1)
          "•"
          (map-indexed
           (fn [i letter]
             ^{:key (str "intro-letter-" i)} [<intro-letter> {:letter letter}])
           (take @*showing-title title)))])]]
   (into [:<>] (ln->br text))
   [:br]
   [:div.buttons
    (for [[text what] buttons] ^{:key (str "intro-button-" text)}
      [(if (string? what) :a.button.background :button.background)
       (if (string? what) {:href what} {:on-click #(r/dispatch what)})
       [:span {:class "blur-bg"}]
       [:span text]])]])


(defc <portfolio> "GOT NO PPORTFOLIE" [])


(defc <service-category-full>
  "Fullscreen version of a services category. Should eventually be like a mini-site/portfolio
   listing any projects done in each..."
  [])

(defc <service-category> {:features [[:seen "zoom-x"]]}
  [{:keys [title icon-name lines full-screened? on-click] :as spec}]
  ;; Preserve the existing category box: moving the transform onto its list
  ;; would change sizing and fullscreen transitions. Motion adds no extra DOM.
  [:div
   (into [:ul {:class (cond (= full-screened? title) "service-fullscreen"
                            full-screened? "service-minimized")
               :on-click on-click}
          [:li [icon/<icon> "fas" icon-name {}] [:h2.service-title title]]]
         (for [line lines] ^{:key (str "service-" title "-" line)} [:li line]))])

(defc <service-categories> [{:keys [categories] :as spec}]
  (let [{:keys [full-screened?]} @(r/subscribe [:state [:services]])]
    [:div#services>div.categories
     {:class (when full-screened? "categories-fullscreened")
      :on-click #(r/dispatch [:state [:services :full-screened?] nil])}
     (for [[title icon-name lines] categories] ^{:key (str "service-" title)}
       [<service-category>
        {:title title :icon-name icon-name :lines lines :full-screened? full-screened?
         :on-click (fn [event]
                     (.stopPropagation event)
                     (r/dispatch [:state [:services :full-screened?]
                                   (when-not full-screened? title)]))}])]))

(defc <services-carousel> "yo"
  [categories]
  (let [full-screened? @(r/subscribe [:state [:services]]) ]
    [:div#services>div.categories
     (for [[title icon-name lines] categories ;(if-not full-screened categories (filter #(= (first %) full-screened) categories)) ;XXX change to keys!!
           :let [id (str "service-" title)]] ^{:key id}
       (into [:ul
               [:li
                [icon/<icon> "fas" icon-name {}]
                [:h3 title]]]
              (for [line lines] ^{:key (str "service-" title "-" line)}
                [:li line])))]))

(defc <services> "List services on (fake) offer. Clicking one should bring it up to fill section..."
  [{:keys [categories bg caption] :as spec}]
  (let [to-focus? (boolean @(r/subscribe [:state [:services :to-focus?]]))]
    ;; One committed owner handles both initial background focus and later
    ;; section intent. Media/category ref replacement must not repeat the action.
    (r/use-effect #(r/dispatch [:focus-element "services-bg"]) [to-focus?]))
  [:section#section-services
    {:class "link-anchor stick-up section-with-media-bg-wrapper"} ; want to  focus elem to zoomy zoom slow after reaching scroll
    [:a {:name "link-services"}]
     [ui-layout/<inset> caption 4] ;auto-gen
     [img/<media-as-bg>
      (merge bg {:id "services-bg"
                 :class "darken-5 parallax-bg"})]
     [<service-categories> {:categories categories}]])


(defc <story> "Big img header + story" [{:keys [heading] :as content}]
  [:<>
   [:div#about-intro {:class "section-with-media-bg-wrapper covering stick-up fullwidth"}
    [ui-layout/<fading-bg-heading> heading]]
   [:div.fader>div.fade-to-black.between]

   [:a {:name "about"}]
   [:section#about.anim-gradient-bg.noborder
    [:h1 (:title content)]
    [:br]
    [layout/<auto-layout-text-imgs> content]
    [:br] [:br]]
   [ui-layout/<fading> :dir "bottom"]])
