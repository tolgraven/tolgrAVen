(ns website.modules.home.pages
  "Page declarations independent of the module implementation."
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require #?(:cljs [website.react :as r])
            #?(:clj [website.build.site :refer [when-enabled]])
            [website.navigation.component-routes :as component-routes]
            #?(:module/github [website.modules.github.routes :as github-routes])))

#?(:clj (when-enabled :github nil
           (require '[website.modules.github.routes :as github-routes])))

#?(:cljs (def controllers
  {:home [{:start (fn [_]
                    (r/dispatch [:state [:is-personal] false])
                    (r/dispatch [:page/init-home]))}]
   :about [{:start (fn [_] (r/dispatch [:scroll/to "about" 700]))}]
   :services [{:start (fn [_]
                        (r/dispatch [:scroll/to "main" 700])
                        (r/dispatch [:scroll/to "section-services" 1300]))}]
   :hire [{:start (fn [_] (r/dispatch [:scroll/to "bottom" 700]))}]}))

(def spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["" {:module :home :page :page :ssr true :data-source :content :kind :landing
         :depends [{:source :strapi :availability :startup
                    :keys [:document :header :common :footer :post-footer]}]
         :preload-images [[:intro :bg 0 :src]]}
    (component-routes/mount
      ["/" {:name :home #?@(:cljs [:controllers (:home controllers)])}]
      (or (when-enabled :github nil github-routes/spec) []))
    ["/about" {:name :about #?@(:cljs [:controllers (:about controllers)])}]
    ["/services" {:name :services #?@(:cljs [:controllers (:services controllers)])}]
    ["/hire" {:name :hire #?@(:cljs [:controllers (:hire controllers)])}]]])
