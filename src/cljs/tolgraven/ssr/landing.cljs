(ns tolgraven.ssr.landing
  "DOM-independent landing markup. Hydration attaches actions to these same nodes."
  (:require [clojure.string :as string]
            [tolgraven.ssr.contract :as contract]))

(defn <image> [data class eager?]
  (when (:src data)
    [:img (merge (select-keys data [:id :src :alt :width :height])
                 {:class class :loading (if eager? "eager" "lazy")
                  :decoding "async"})]))

(defn <intro> [{:keys [title text bg logo-bg buttons]} {:keys [contact! email]}]
  [:section#intro
   [:div#logo-top.logo-bg.stick-up.logo-tolgraven
    {:style {:background-image (str "url('" logo-bg "')")}}]
   [<image> (assoc (first bg) :id "top-banner") "media media-as-bg" true]
   [:div.h1-wrapper.center-content [:h1.h-responsive.h-intro title]]
   (for [[i line] (map-indexed vector (string/split-lines (or text "")))]
     ^{:key i} [:p line])
   [:br]
   [:div.buttons
    (for [[label action] buttons]
      ^{:key label}
      [:a.background.ssr-action
       (if (string? action) {:href action}
         ;; Public CMS actions are a small semantic allowlist, never arbitrary
         ;; event vectors executed from server/editor data.
         {:href (str "mailto:" email)
          :on-click (when (and contact! (contains? #{[:state [:contact-form :show?] true]
                                                     ["state" ["contact-form" "show?"] true]} action))
                      (fn [event] (.preventDefault event) (contact!)))})
       [:div.blur-bg] label])]])

(defn <services> [{:keys [categories bg caption]} {:keys [service! selected]}]
  [:section#section-services.link-anchor.stick-up.section-with-media-bg-wrapper
   [:a {:name "link-services"}]
   [<image> bg "media media-as-bg darken-5 parallax-bg" false]
   [:p.caption caption]
   [:div#services
    [:div.categories {:class (when selected "categories-fullscreened")}
     (for [[title icon lines] categories]
       ^{:key title}
       [:ul {:class (cond (= selected title) "service-fullscreen" selected "service-minimized")}
        [:li [:button.noborder
              {:type "button" :aria-expanded (= selected title)
               :on-click (when service! #(service! (when-not (= selected title) title)))}
              [:i {:class (str "fas fa-" icon)}] [:h3 title]]]
        (for [line lines] ^{:key line} [:li line])])]]])

(defn <interlude> [{:keys [title caption bg]} index]
  [:section.nopadding.section-with-media-bg-wrapper.parallax-wrapper {:id (str "interlude-" index)}
   (if (re-find #"(?i)\.(mp4|mov|webm)(?:\?.*)?$" (or (:src bg) ""))
     [:video.media.media-as-bg
      (merge (select-keys bg [:src :poster])
             {:muted true :plays-inline true :loop true :controls true :preload "none"})]
     [<image> bg "media media-as-bg" false])
   [:div.covering-faded.widescreen-safe.center-content.parallax-group [:h1.h-responsive title]]
   [:p.caption caption]])

(defn <story> [{:keys [heading title text images]}]
  [:<>
   [:div#about-intro.section-with-media-bg-wrapper.covering.stick-up.fullwidth
    [<image> (:bg heading) "media media-as-bg" false]
    [:h1.h-responsive (:title heading)]]
   [:section#about.anim-gradient-bg.noborder
    [:h1 title]
    (let [lines (string/split-lines (or text ""))
          size (max 1 (int (js/Math.ceil (/ (count lines) (max 1 (count images))))))
          chunks (vec (partition-all size lines))]
      (into [:div.float-wrapper]
        (mapcat (fn [i]
                  (let [[id data caption side] (get images i)]
                    [(when data
                       ^{:key (str "image-" i)}
                       ;; Bare CMS IDs such as "cljs" become named window
                       ;; properties before JS boots, hijacking Closure namespaces.
                       [:figure.float-with-caption {:id (str "story-image-" id) :class (or side "left")}
                        [<image> data "media image-inset" false]
                        (when caption [:figcaption caption])])
                     ^{:key (str "text-" i)}
                     [:div (for [[n line] (map-indexed vector (get chunks i))]
                             ^{:key n} [:p line])]]))
                (range (max (count images) (count chunks))))))]])

(defn <moneyshot> [{:keys [title caption bg]}]
  [:div#moneyshot.section-with-media-bg-wrapper.parallax-wrapper.covering.stick-up
   [<image> bg "media media-as-bg darken-8 parallax-bg origin-toptop" false]
   [:section#intro-end.center-content [:h1.h0-responsive.parallax-fg title]]
   [:p.caption caption]])

(defn <deferred> [id island]
  [:section.ssr-module {:id (str "ssr-module-" (name id)) :data-module (name id)}
   (if island [island id]
     [:p {:role "status"} (str (string/capitalize (name id)) " loads when you approach this section.")])])

(defn <page> [content {:keys [island] :as actions}]
  (into [:<>]
    (keep-indexed
     (fn [index entry]
       (let [[id arg] (if (vector? entry) entry [entry])
             view (case id
                    :init nil
                    :intro [<intro> (:intro content) actions]
                    :services [<services> (:services content) actions]
                    :interlude [<interlude> (get (:interlude content) arg) arg]
                    :moneyshot [<moneyshot> (:moneyshot content)]
                    :story [<story> (:story content)]
                    :gallery [:section#gallery.covering.fullwide
                              [:div.sideways
                               (for [data (:gallery content)]
                                 ^{:key (:src data)} [<image> data "media" false])]]
                    [<deferred> id island])]
         (when view (with-meta view {:key index}))))
     contract/landing-layout)))
