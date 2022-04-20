(ns tolgraven.instagram.views
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [tolgraven.util :as util :refer [at]]))

(defn instagram-post "An instagram post"
  [post item]
  (let [hover? (r/atom false)]
    (fn [post item]
      [:div.instagram-item
       {:on-click #(rf/dispatch [:modal-zoom :fullscreen :open item])
        :on-mouse-enter #(reset! hover? true)
        :on-mouse-leave #(reset! hover? false)}
       item
       (when @hover?
         [:div.instagram-caption
          (or (:caption post) "We will be with you in a moment.")])])))


(defn instagram "Instagram gallery"
  []
  (let [amount (r/atom 24)
        posts (rf/subscribe [:instagram/posts @amount])]
    (fn []
      [:section#gallery-3.fullwide.covering
       [:div.covering.instagram
        (for [post (or @posts (range @amount))
              :let [item [:img {:class (when-not (:media_url post) "transparent-border")
                                :src (or (:media_url post) "img/logo/instagram-fallback-logo.png")
                                :on-error #(rf/dispatch [:instagram/fetch-from-insta [(:id post)]])}]]] ; dispatch on failed fetch due to url expiry
          ^{:key (str "instagram-" (or (:id post) post))}
          [instagram-post post item])]])))

