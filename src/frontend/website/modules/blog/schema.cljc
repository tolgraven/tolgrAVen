(ns website.modules.blog.schema
  #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  #?(:cljs (:require #?(:clj [website.build.site :refer [when-enabled]]) [website.schema.common :as c]
            [website.schema.declarations :as declarations]
            [website.supabase.schema :as store] )))

#?(:cljs
   (do
(def return-state-keys
  [:current-post-id :viewing-tag :page :comment-limit :comments-expanded
   :comment-thread-expanded :adding-comment])

(def state
  [:map
   [:?restore-edits [:set [:tuple [:enum :comment-thread-expanded :comment-limit :adding-comment :comments-expanded]
           :any]]]
   [:?current-post-id [:maybe c/id]]
   [:?viewing-tag :?string]
   [:?comments-expanded [:map-of c/id :boolean]]
   [:?adding-comment [:map-of c/path :?boolean]]
   [:?page [:int {:min 0}]]
   [:?comment-limit [:map-of :any [:int {:min 1}]]]
   [:?comment-thread-expanded [:map-of :any :boolean]]])
(def options [:map [:?posts-per-page [:int {:min 1}]]])
(def sections {[:state :blog] state [:options :blog] options})

(def post-header-spec [:map [:children [:sequential :any]]])
(def displayed-post
  (vector :merge store/post [:map [:?user [:?or c/id store/profile]]]))
(def post-spec (declarations/extend-spec [:map [:post displayed-post]]))
(when-enabled :blog :comments
  (def displayed-comment
  (vector :merge store/comment-record [:map [:?user [:?or c/id store/profile]]])))
(when-enabled :blog :comments
  (def comment-spec (declarations/extend-spec [:map [:path c/path] [:comment displayed-comment]
                                            [:?focused-id [:maybe c/id]]
                                            [:?quote? :boolean]
                                            [:?visible? [:maybe c/derefable]]])))
(when-enabled :blog :comments
  (def parent-spec (declarations/extend-spec [:map [:parent-path c/path]])))
(when-enabled :blog :comments
  (def reply-row-spec
    (declarations/extend-spec [:map [:expanded? :boolean]
                                    [:children [:sequential :any]]])))
(def posted-by-spec
  (declarations/extend-spec
   (c/optional-map {:id [:maybe c/id] :user [:?or c/id store/profile]
                    :ts [:maybe number?] :score [:maybe number?]})))
(def tag-spec [:map [:tag :string]])

(def post-fields [:maybe (c/optional-map {:title :string
                                          :text :string
                                          :tags [:or :string c/strings]})])
(when-enabled :blog :comments
  (def comment-fields [:map-of c/path [:maybe (c/optional-map {:title :?string
                                                             :text :?string})]]))

(def post-records [:?map-of c/id displayed-post])
(when-enabled :blog :comments
  (def comment-records [:?map-of c/id displayed-comment]))
(def post-list [:?sequential displayed-post])
(def navigation-action [:or [:enum :prev :next] c/positive])
(def no-args [:tuple])
(def id-args [:tuple c/id])
(def selected-id-args [:tuple [:maybe c/id]])
(def path-args [:tuple c/path])
(def thread-args [:cat c/id [:? [:maybe c/id]]])
(def page-args [:tuple c/nonnegative c/positive])
(when-enabled :blog :comments
  (def root-page
  [:map
   [:records comment-records]
   [:loading? :boolean]
   [:more? :boolean]]))

(when-enabled :blog :comments
  (def focus-state
    [:maybe [:map
             [:ready? :boolean]
             [:comment-id :string]
             [:row [:maybe displayed-comment]]
             [:root [:maybe displayed-comment]]
             [:parent [:maybe displayed-post]]]]))

(def positive-input
  [:or c/positive [:and :string [:re #"^[+]?0*[1-9][0-9]*$"]]])

(def event-args
  (into {} (remove (comp nil? val)) {:blog/init no-args
   :blog/init-posting no-args
   :blog/edit-post [:tuple displayed-post]
   :blog/cancel-edit no-args
   :blog/state [:tuple c/path :any]
   :blog/set-posts-per-page [:tuple positive-input]
   :blog/nav-action [:tuple navigation-action]
   :blog/nav-page [:tuple positive-input]
   :blog/submit [:tuple post-fields [:maybe displayed-post]]
   :blog/post-saved [:tuple post-fields :map]
   :blog/edit-comment (when-enabled :blog :comments [:tuple c/path displayed-comment])
   :blog/cancel-comment (when-enabled :blog :comments path-args)
   :blog/comment-submit (when-enabled :blog :comments [:tuple c/path [:map [:text :string]] [:maybe displayed-comment]])
   :blog/comment-saved (when-enabled :blog :comments [:tuple c/path :map :map])
   :blog/write-failed [:tuple [:or :keyword c/path] :map]
   :blog/comment-vote (when-enabled :blog :comments [:tuple [:?or c/id store/profile] store/profile c/path [:enum :up :down]])
   :blog/vote-saved (when-enabled :blog :comments [:tuple :string [:map [:vote [:enum -1 0 1]]]])
   :blog/cache-state-changed no-args
   :blog/expand-comment-thread (when-enabled :blog :comments [:tuple c/path :boolean])
   :blog/adding-comment (when-enabled :blog :comments [:tuple c/path :?boolean])
   :blog/load-more-comments (when-enabled :blog :comments id-args)}))
))
