(ns website.modules.blog.metadata
  "Native blog author and timestamp presentation, shared with lightweight previews."
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.blog.schema :as schema]))

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
