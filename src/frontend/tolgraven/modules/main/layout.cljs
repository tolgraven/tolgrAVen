(ns tolgraven.modules.main.layout
  (:require [tolgraven.content.contract :as content]
            [tolgraven.modules.main.pages :as main-pages]
            [reitit.core :as reitit]))

(def landing-paths
  (into #{} (comp (filter #(= :landing (:kind (second %)))) (map first))
        (reitit/routes (reitit/router main-pages/spec))))

;; The ordinary client layout and SSR use the same ordering. :init entries are
;; browser lifecycle instructions, not extra server-rendered DOM.
(def landing-layout
  [:intro [:interlude 0] :services [:init :services] [:interlude 1]
   :moneyshot [:init :instagram] [:init :strava] :story [:interlude 2]
   [:init :soundcloud] :strava [:init :gallery] :soundcloud :instagram
   :gallery :github :gpt :chat])

(def landing-spec
  {:id :landing :layout landing-layout
   :depends [{:source :strapi :keys (content/keys-for-route :home)}]
   :deferred-modules #{:strava :soundcloud :instagram :github :gpt :chat}})

(defn page-spec [uri]
  (when (landing-paths uri) landing-spec))
