(ns tolgraven.modules.markdown-components.widgets
  #?(:cljs (:require-macros [tolgraven.build.site :refer [when-enabled]]))
  #?(:cljs (:require [tolgraven.component.registry]
                     [tolgraven.macros :as m :refer-macros [defc]]
                     [tolgraven.react :as r]
                     [tolgraven.components.image :as image]
                     [tolgraven.components.highlight :as highlight]
                     [tolgraven.components.oembed :as oembed]
                     [tolgraven.modules.markdown-components.catalog :as catalog]
                     [tolgraven.components.content.box]
                     [tolgraven.components.content.markdown]
                     [tolgraven.components.content.frame])))

#?(:cljs
(do
(when-enabled :settings nil
  (defc <config-table>
    "Interactive site appearance settings. Readers can toggle the actual light/dark theme and adjust spacing, line width and corner rounding; these are their local preferences."
    {:authoring {:category "Widgets"
                 :defaults {:title "Appearance"}}
     :spec-schema [:map [:?title :string]]}
    [{:keys [title]}]
    (m/<> :settings/config-table {:title title})))

(when-enabled :search nil
  (defc <site-search>
    "Search the site's published blog posts and comments using the normal search service. Results remain interactive links to their original content."
    {:authoring {:category "Widgets"}
     :spec-schema [:map]}
    [_]
    (m/<> :search/inline)))

(defc <embed>
  "Embed a YouTube video or SoundCloud track with the site's existing oEmbed player and managed loading state. Supply its public HTTPS URL."
  {:authoring {:category "Media"
               :defaults {:url "https://www.youtube.com/watch?v=dQw4w9WgXcQ"}}
   :spec-schema [:map [:url [:re "^https://(?:www\\.)?(?:youtube\\.com|youtu\\.be|soundcloud\\.com)/"]]]}
  [{:keys [url]}]
  [oembed/<oembed-view> url [:p "Loading player…"]])

(when-enabled :blog :comments
  (defc <comment-quote>
    "Quote a comment from another blog post in the site's comment UI, including author, timestamp and a link to its original post. The component declares its public data dependency."
    {:authoring {:category "Blog"}
     :spec-schema [:map [:post-id [:or :string :int]] [:comment-id [:or :string :int]]]}
    [spec]
    (m/<> :blog/comment-quote spec)))


))
