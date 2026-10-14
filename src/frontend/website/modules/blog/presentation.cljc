(ns website.modules.blog.presentation
  "Presentation shared by posts, optional comments, previews and authoring."
  #?(:cljs (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.modules.blog.metadata :as metadata]
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

(defc <posted-by>
  "Keep the existing public component identity while sharing native metadata presentation."
  {:features [[:appear nil]]}
  [spec :- schema/posted-by-spec]
  (metadata/render-posted-by spec))

))
