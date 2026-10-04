(ns tolgraven.instagram.views
  (:require
   [reagent.core :as r]
   [tolgraven.react :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.image :as img]
   [tolgraven.util :as util :refer [at]]))

(defn instagram-post "An instagram post"
  [post item]
  (let [hover? (r/atom false)]
    (fn [post item]
      [ui/seen-merge "opacity"
       [:div.instagram-item
        {:on-click #(rf/dispatch [:modal-zoom :fullscreen :open item])
         :on-mouse-enter #(reset! hover? true)
         :on-mouse-leave #(reset! hover? false)}
        item
        (when @hover?
          [:div.instagram-caption
           (or (:caption post) "We will be with you in a moment.")])]])))


(defn instagram "Instagram gallery"
  []
  (let [amount (r/atom 24)
        posts (rf/subscribe [:instagram/posts @amount])
        fallback-url "img/logo/instagram-fallback-logo.png"
        fallback (r/atom nil)] ; would need to be a map of ids to status, so it's per img
    (fn []
      [:section#gallery-3.fullwide.covering
       [:div.covering.instagram
        ; {:ref #(when (and % @posts)
        ;          (reset! fallback nil))} ; wouldn't work
        [:<>
         (doall
          (for [post (or @posts (range @amount)) ; empty seq triggers fallbacks
                :let [url @(rf/subscribe [:href-external-img
                                          (:media_url post)
                                          "fit-in" "800x800"])
                      item [img/picture {:class (when (or (not (:media_url post))
                                                          @fallback)
                                                  "transparent-border")
                                         :alt (or (:caption post) "Instagram post")
                                         :src (or @fallback ; would cause all to show fallback if one errors
                                                  (:media_url post) ; signed CDN URLs work without an image proxy
                                                  url
                                                  fallback-url)
                                         :on-error (fn [_]
                                                     ;; The server-owned feed replaces the removed per-post API.
                                                     ;; Preserve the fallback without dispatching its obsolete event.
                                                     (reset! fallback fallback-url)
                                                     #_(if-not @fallback
                                                         (rf/dispatch [:instagram/fetch-from-insta [(:id post)]]) ; dispatch on failed fetch due to url expiry
                                                         (reset! fallback fallback-url)))}]]] ; in the meantime (til fetch comes through to sub) use fallback ratom
            ^{:key (str "instagram-" (or (:id post) (random-uuid)))}
            [instagram-post post item]))]]])))

