(ns website.modules.blog.presentation
  "Presentation shared by posts, optional comments, previews and authoring."
  #?(:cljs (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.blog.schema :as schema]
            #?(:module/link-preview [website.modules.link-preview.interface :as link-preview]
               :default [website.modules.markdown.interface :as markdown]))))

#?(:cljs
   (do

(def code-languages
  ;; Keep Lisp-family false positives out of this Clojure-centric blog.
  ["clojure" "cpp" "javascript" "json" "bash" "css" "xml" "python" "sql" "diff"])

(defc <markdown>
  "This blog falls back to Clojure when code has no tag or detectable language."
  [text & [options]]
  [#?(:module/link-preview link-preview/<md>
      :default markdown/<md->div>)
   text
   (merge {:default-language "clojure"
           :auto-languages code-languages} options)])

(defn link-trust [user]
  (let [id (if (string? user) user (:id user))]
    (cond
      (nil? id) :untrusted
      @(r/subscribe [:user/trusted? id]) :trusted
      :else :user)))

(defc <comment-markdown>
  "Comments support ordinary Markdown and images, never authored site components or raw HTML."
  [text & [options]]
  [<markdown> text (assoc options
                         :allow-images? true
                         :allow-raw? false
                         :allow-components? false)])

(defc <posted-by> "Render author, timestamp, and optional score."
  {:features [[:appear nil]]}
  [{:keys [id user ts score] :or {score 0} :as spec} :- schema/posted-by-spec]
  (let [user @(r/subscribe [:user/user user])
        username [:em.blog-user
                  (if-let [username (:name user)]
                    username
                    "anon")]
        ts @(r/subscribe [:timestamp ts])]
    [:span.blog-info
     username
    (when @(r/subscribe [:user/trusted? (:id user)])
      [:span {:style {:font-size "80%"}}
       "admin"])
     [:span ts]
     (when-not (= 0 score)
       [:span (cond (pos? score) "+"
                    (neg? score) "")
        score])]))

))
