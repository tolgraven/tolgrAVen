(ns tolgraven.modules.docs.pages
  "Page declarations independent of the module implementation."
  (:require [tolgraven.schema.http :as schemas]
            #?(:clj [tolgraven.env :as environment])
            #?(:cljs [tolgraven.react :as rf])))

(defn document-dependency
  "Generated Codox HTML comes from our backend, independently of CMS framing.
   The path binding reuses SSR/restored HTML; the URL adapter deduplicates reads."
  [page]
  (let [page (or page "index")]
    {:source :app-db :path [:docs page]
     :load {:source :url :url (str "/api/doc?path=" page)
            :format :text :into [:docs page]}}))

#?(:cljs (do
  (defn- activate! [page]
    (rf/dispatch [:docs/get page])
    (rf/dispatch [:docs/set-page page]))
  (def controllers
    {:docs [{:start (fn [_] (activate! "index"))}]
     :docs-codox-page [{:parameters {:path [:doc]}
                       :start (fn [{:keys [path]}] (activate! (:doc path)))}]})))

(def development? #?(:clj (:development? environment/defaults) :dev true :default false))

(def ordinary-spec
  ;; Native Reitit routes, with shared data inherited by each child page.
  [["/docs" {:module :docs :page :page :ssr true :streaming false :data-source :docs
            :depends [{:source :strapi :keys [:docs]}]}
    ["" {:name :docs :selection {:doc "index"}
         #?@(:cljs [:controllers (:docs controllers)])}]
    ["/codox/:doc" {:parameters {:path schemas/docs-page}
                    :name :docs-codox-page
                    :ssr-parameters {:doc {:from :doc, :type :document}}
                    #?@(:cljs [:controllers (:docs-codox-page controllers)])}]]])

(def spec
  (if development?
    (update ordinary-spec 0 into
      [["/source/*file" {:name :docs-source
                         :page :source
                         :ssr false
                         :depends []
                         :data-source nil}]
       ["/source" {:name :docs-source-index
                    :page :source
                    :ssr false
                    :depends []
                    :data-source nil}]])
    ordinary-spec))
