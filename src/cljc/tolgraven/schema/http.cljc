(ns tolgraven.schema.http
  (:require #?(:clj [reitit.coercion.malli :as malli]
               :cljs [tolgraven.schema.page-coercion :as page])))

;; Open maps preserve application extension/query keys. Only declared fields are
;; coerced; do not strip OAuth/debug query parameters or untyped operation bodies.
(def coercion
  #?(:cljs page/coercion
     :clj (malli/create {:compile (fn [schema _] schema)
                        :strip-extra-keys false
                        :error-keys #{:type :in :humanized}})))
(def page-number [:int {:min 1 :max 999999}])
(def document-name [:and :string [:re #"^[A-Za-z0-9_.-]+$"]
                    [:fn {:error/message "must name a documentation page"} #(not= ".." %)]])
(def blog-page [:map [:nr page-number]])
(def blog-post [:map [:permalink [:and :string [:re #"^(?:.*-)?\d{1,15}$"]]]])
(def blog-tag [:map [:tag [:string {:min 1 :max 200}]]])
(def docs-page [:map [:doc document-name]])
(def page-query [:map [:userBox {:optional true} :boolean]
                     [:settingsBox {:optional true} :boolean]])
(def docs-query [:map [:path document-name]])
(def url-query [:map [:url [:string {:min 1}]]])
(def messages [:map [:messages [:sequential :string]]])
(def contact [:map [:name :string] [:email :string] [:title :string] [:message :string]])
(def operands [:map [:x :int] [:y :int]])
(def positive-total [:map [:total pos-int?]])

(def upload
  [:map {:swagger/type "file"}
   [:filename :string] [:content-type :string] [:size [:int {:min 0}]]
   [:tempfile #?(:clj [:fn {:error/message "must be an uploaded file"}
                       #(instance? java.io.File %)] :cljs :any)]])

;; Service paths are allowlisted values, never arbitrary upstream URLs.
(defn service-path [pattern]
  [:and :string [:re pattern]
   [:fn {:error/message "must be an allowed service path"}
    #(not (re-find #"(?i)(?:\.\.|%2e|%2f|%5c|\\|://)" %))]])
(def strava-path (service-path #"(?:athlete(?:/activities)?|athletes/[0-9]+/stats|segments/starred|gear/[A-Za-z0-9]+|activities/[0-9]+(?:/(?:streams|kudos))?|segments/[0-9]+/streams)(?:\?[A-Za-z0-9_=,&.-]*)?"))
(def intervals-path (service-path #"athlete-summary(?:\{ext\})?(?:\?start=[0-9-]+&end=[0-9-]+)?"))
(def strapi-path (service-path #"/api/[A-Za-z0-9/_-]+(?:\?[A-Za-z0-9_=&%\[\].,-]*)?"))
(def search-query
  [:map [:collection [:enum "blog-posts" "blog-comments"]]
   [:q [:string {:min 1 :max 200}]]
   [:page {:optional true} [:int {:min 1 :max 10000}]]
   [:per_page {:optional true} [:int {:min 1 :max 100}]]])
(def image-query
  [:map [:url [:string {:min 1}]]
   [:transforms {:optional true} [:and :string [:re #"(?:fit-in/)?[1-9][0-9]{0,3}x[1-9][0-9]{0,3}|"]]]])
