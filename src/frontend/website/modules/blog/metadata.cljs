(ns website.modules.blog.metadata
  "Native blog author and timestamp presentation, shared with lightweight previews."
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.blog.model :as model]
            [website.modules.blog.schema :as schema]))

(defc <tag-link> [{:keys [tag]} :- schema/tag-spec]
  [:span [:a.blog-tag-link {:href @(r/subscribe [:href :blog-tag {:tag tag}])} tag]])

(defn render-tags-list [{{:keys [id tags]} :post}]
  (when-let [tags' (seq (model/tags tags))]
    [:div.blog-post-tags
     (for [tag (sort tags')]
       ^{:key (str "blog-post-" id "-category-" tag)}
       [<tag-link> {:tag tag}])]))

(defc <tags-list> [spec :- schema/post-spec]
  (render-tags-list spec))

(defn render-posted-by
  "Shared native form; each public component retains its own appearance contract."
  [{:keys [id user ts score] :or {score 0} :as spec}]
  (let [user' @(r/subscribe [:user/user user])
        username [:em.blog-user
                  (if-let [username (:name user')]
                    username
                    "anon")]
        ts' @(r/subscribe [:timestamp ts])]
    [:span.blog-info
     username
    (when @(r/subscribe [:user/trusted? (:id user')])
      [:span {:style {:font-size "80%"}}
       "admin"])
     [:span ts']
     (when-not (= 0 score)
       [:span (cond (pos? score) "+"
                    (neg? score) "")
        score])]))

(defc <posted-by> "Render author, timestamp, and optional score."
  {:features [[:appear nil]]}
  [spec :- schema/posted-by-spec]
  (render-posted-by spec))
