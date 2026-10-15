(ns website.modules.main.layout
  (:require [website.content.contract :as content]
            [website.page-layout :as page-layout]
            #?(:module/home [website.modules.home.pages :as home])
            [website.site :as site]
            [reitit.core :as reitit]))

(def landing-pages
  (into {} (filter #(= :landing (:kind (second %))))
        (reitit/routes (reitit/router (site/routes #?(:module/home home/spec :clj [] :default []))))))

(def landing-paths (set (keys landing-pages)))

;; The ordinary client layout and SSR use the same published section ordering.
(def landing-layout
  (mapv (fn [{:keys [component index]}]
          (if (some? index) [(keyword component) index] (keyword component)))
        (:sections (page-layout/find-page (page-layout/selected-pages site/config) "home"))))

(def landing-spec
  {:id :landing :layout landing-layout
   :depends [{:source :strapi :keys (content/keys-for-route :home)}]
   :deferred-modules #{:strava :soundcloud :instagram :github :codex-profile :gpt :chat}})

(defn page-spec [uri]
  (when-let [page (get landing-pages uri)]
    (assoc landing-spec :layout-id (:layout page))))

(defn same-layout? [from to]
  (let [page (page-spec from)]
    (and page (= (:layout-id page) (:layout-id (page-spec to))))))
