(ns tolgraven.blog.ssr-view
  "Pure public view shared by the Node renderer and the first client render.
   No subscriptions, browser globals, clocks, random IDs, or mount animations."
  (:require ["react-markdown" :default Markdown]
            ["remark-gfm" :default gfm]))

(defn post-href [{:keys [id permalink]}]
  (str "/blog/post/" (js/encodeURIComponent (or permalink (str id)))))

(defn <post> [{:keys [id title text author date tags] :as post} comments]
  [:section.blog-post {:id (str "blog-post-" id)}
   [:div.flex.blog-post-header
    [:div.blog-post-header-main
     [:a {:href (post-href post)} [:h1.blog-post-title title]]
     [:span.blog-info [:em.blog-user (or (:name author) "anon")] [:time date]]
     [:div.blog-post-tags
      (for [tag tags]
        ^{:key tag} [:span [:a.blog-tag-link {:href (str "/blog/tag/" (js/encodeURIComponent tag))} tag]])]]]
   [:div.blog-post-text
    ;; Raw HTML is deliberately disabled. ReactMarkdown's URL transform also
    ;; rejects executable links. Identical options run on server and browser.
    [:> Markdown {:remarkPlugins #js [gfm]} (or text "")]]
   [:div.blog-ssr-comments {:data-post-id id :style {:min-height "4rem"}}
    (if comments [comments {:post post}]
      [:p {:role "status"} "Comments load when the page is ready."])]])

(defn <page> [{:keys [posts content page more? missing?]} & [comments notices]]
  (let [[brand tagline] (get-in content [:header :text])
        menu (mapcat #(get-in content [:header :menu %]) [:work :personal])]
    [:<>
     [:header
      [:div.header-logo
       [:a {:href "/"} [:h1 brand]]
       [:div.header-logo-text (for [line tagline] ^{:key line} [:p line])]]
      [:menu [:nav (for [[label href] menu]
                    ^{:key label} [:a {:href href} label])]]]
     [:div.line.line-header]
     (when notices [notices])
     [:main#main.main-content
      [:section.blog.fullwide.noborder
       [:h1 (get-in content [:blog :heading :title])]
       (when missing? [:p {:role "alert"} "This blog post could not be found."])
       (for [post posts] ^{:key (:id post)} [<post> post comments])
       (when page
         [:nav.blog-nav.center-content {:aria-label "Blog pages"}
          (when (> page 1) [:a {:href (str "/blog/page/" (dec page))} "Newer posts"])
          [:span (str "Page " page)]
          (when more? [:a {:href (str "/blog/page/" (inc page))} "Older posts"])])
       [:div.flex.center-content
        [:a.blog-btn {:href "/blog"} "Home"]
        [:a.blog-btn {:href "/blog/archive"} "Archive"]]]]
     [:footer.footer-sticky
      [:div.footer-content
       (for [{:keys [id email text links]} (:footer content)]
         ^{:key id}
         [:div
          (when email [:a {:href (str "mailto:" email)} email])
          (for [line text] ^{:key line} [:p line])
          (for [{:keys [name href]} links] ^{:key name} [:a {:href href} name])])]]]))
