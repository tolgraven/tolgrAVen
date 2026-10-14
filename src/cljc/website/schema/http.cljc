(ns website.schema.http
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require [website.schema.dsl :as dsl]
            #?(:clj [website.build.site :refer [when-enabled]])
            #?(:clj [reitit.coercion.malli :as malli]
               :cljs [website.schema.page-coercion :as page])))

;; Open maps preserve application extension/query keys. Only declared fields are
;; coerced; do not strip OAuth/debug query parameters or untyped operation bodies.
;; Reitit lite parses before :compile; use native syntax so our DSL expands first.
(def coercion
  #?(:cljs page/coercion
     :clj (malli/create {:lite false
                        :compile (fn [schema _] (dsl/->malli schema))
                        :strip-extra-keys false
                        :error-keys #{:type :in :humanized}})))
(when-enabled :blog nil
  (def page-number [:int {:min 1 :max 999999}])
  (def blog-page [:map [:nr page-number]])
  (def blog-post [:map [:permalink [:and :string [:re #"^(?:.*-)?\d{1,15}$"]]]])
  (def blog-tag [:map [:tag [:string {:min 1 :max 200}]]]))
(when-enabled :docs nil
  (def document-name [:and :string [:re #"^[A-Za-z0-9_.-]+$"]
                      [:fn {:error/message "must name a documentation page"} #(not= ".." %)]])
  (def docs-page [:map [:doc document-name]])
  (def docs-query [:map [:path document-name]]))
(def page-query [:map [:?userBox :boolean]
                     [:?settingsBox :boolean]])
(def url-query [:map [:url [:string {:min 1}]]])
(when-enabled :gpt nil
  (def messages [:map [:messages [:sequential :string]]]))
(when-enabled :contact nil
  (def contact [:map [:name :string] [:email :string] [:title :string] [:message :string]]))
(def operands [:map [:x :int] [:y :int]])
(def positive-total [:map [:total pos-int?]])

(when-enabled :user nil
  (def upload
    [:map {:swagger/type "file"}
     [:filename :string] [:content-type :string] [:size [:int {:min 0}]]
     [:tempfile #?(:clj [:fn {:error/message "must be an uploaded file"}
                         #(instance? java.io.File %)] :cljs :any)]]))

;; Service paths are allowlisted values, never arbitrary upstream URLs.
(defn service-path [pattern]
  [:and :string [:re pattern]
   ;; Malli :re accepts a substring; service paths must match the whole value.
   [:fn {:error/message "must exactly match an allowed service path"}
    #(boolean (re-matches pattern %))]
   [:fn {:error/message "must be an allowed service path"}
    #(not (re-find #"(?i)(?:\.\.|%2e|%2f|%5c|\\|://)" %))]])
(when-enabled :strava nil
  (def strava-path (service-path #"(?:athlete(?:/activities)?|athletes/[0-9]+/stats|segments/starred|gear/[A-Za-z0-9]+|activities/[0-9]+(?:/(?:streams|kudos))?|segments/[0-9]+/streams)(?:\?[A-Za-z0-9_=,&.-]*)?"))
  (def intervals-path (service-path #"athlete-summary(?:\{ext\})?(?:\?start=[0-9-]+&end=[0-9-]+)?")))
(when-enabled :main :services
  (def strapi-path (service-path #"/api/[A-Za-z0-9/_-]+(?:\?[A-Za-z0-9_=&%\[\].,-]*)?"))
  (def image-query
    [:map [:url [:string {:min 1}]]
     [:?transforms [:and :string [:re #"(?:fit-in/)?[1-9][0-9]{0,3}x[1-9][0-9]{0,3}|"]]]]))
(when-enabled :search nil
  (def search-query
    [:map [:collection (into [:enum "blog-posts"] (when-enabled :blog :comments ["blog-comments"]))]
     [:q [:string {:min 1 :max 200}]]
     [:?page [:int {:min 1 :max 10000}]]
     [:?per_page [:int {:min 1 :max 100}]]]))
