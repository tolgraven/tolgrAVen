(ns website.modules.blog.comment-views
  "Optional comment presentation; the blog shell remains in views."
  (:require [clojure.string :as string]
            [website.react :as r]
            [website.components.timer :as timer]
            [website.components.portal :as portal]
            [website.listener :as listener]
            [website.modules.blog.comments :as comments]
            [website.modules.blog.schema :as schema]
            [website.modules.blog.presentation :as presentation]
            [website.modules.markdown-editor.model :as editor-model]
            [website.modules.markdown-editor.preview :as preview]
            [website.components.content.media :as authored-media]
            [website.media.schema :as media-schema]
            [website.macros :as m :refer-macros [defc]]
            [website.schema.common :as c]
            [website.component.loading :as loading]
            [website.util :as util]))

(declare <add-comment>)

(defc <comment-action-label>
  "Keep the label outside the comment's existing clipping boundary."
  [{:keys [id label left top]} close!]
  (r/use-effect
    #(listener/listen! :comment-action-label
       [{:target "window" :event "scroll" :capture? true :handler close!}
        {:target "window" :event "resize" :handler close!}])
    [close!])
  [portal/<portal> js/document.body
   [:span.blog-comment-action-label
    {:id id
     :role "tooltip"
     :style {:left (str left "rem") :top (str top "rem")}}
    label]])

(defc <comment-action>
  "Native link or button with an owned hover/focus label, including Escape dismissal."
  {:icons ["solid/link" "solid/comments" "solid/reply" "solid/edit"]
   :spec-schema [:map
                 [:label :string]
                 [:icon :string]
                 [:?class :string]
                 [:?href :string]
                 [:?on-click fn?]]}
  [{:keys [label icon class href on-click]}]
  :let [*hover? (r/atom false)
        *focus? (r/atom false)
        *dismissed? (r/atom false)
        *anchor (r/atom nil)
        capture-anchor! (fn [event]
                          (let [rect (.getBoundingClientRect (.-currentTarget event))
                                unit (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))]
                            (reset! *anchor {:left (/ (.-right rect) unit)
                                             :top (/ (.-bottom rect) unit)})))]
  (let [id (r/use-id)
        open? (and (or @*hover? @*focus?) (not @*dismissed?))]
    [:<>
     [(if href :a.button.blog-btn.blog-comment-action.noborder :button.blog-btn.blog-comment-action.noborder)
     {:class class
      :href href
      :type (when-not href "button")
      :aria-label label
      :aria-describedby (when open? id)
      :on-click on-click
      :on-pointer-enter #(do (capture-anchor! %) (reset! *hover? true) (reset! *dismissed? false))
      :on-pointer-leave #(reset! *hover? false)
      :on-focus #(do (capture-anchor! %) (reset! *focus? true) (reset! *dismissed? false))
      :on-blur #(reset! *focus? false)
      :on-key-down #(when (= "Escape" (.-key %)) (reset! *dismissed? true))}
     [:i {:class (str "fa fa-" icon), :aria-hidden true}]]
     (when (and open? @*anchor)
       [<comment-action-label> (assoc @*anchor :id id :label label)
        #(reset! *dismissed? true)])]))

(defc <preview-comment> "Render the current comment draft as Markdown."
  {:features [[:appear nil]]}
  [{:keys [model] :as spec}]
  (let [{:keys [user title text]} @model]
    [:div.blog-comment-preview
     {:style {:min-height "7.35rem"}}
     (when title
       [:h3.blog-comment-title title])
     [presentation/<comment-markdown> text {:trust (presentation/link-trust user)
                  :image-assets (:image-assets spec)
                  :image-readiness (:image-readiness spec)}]]))

(defc <add-comment-btn> "Open or cancel the comment editor."
  [{:keys [parent-path kind] :as spec} :- schema/parent-spec]
  (let [adding-comment? @(r/subscribe [:comments/adding? parent-path])
        attrs {:on-click
               #(r/dispatch
                 [:blog/adding-comment parent-path (not adding-comment?)])}]
    (case kind
      :comment (when-not adding-comment?
                 [:button.blog-btn.topborder
                  attrs "Add comment"])
      :reply   (when-not adding-comment?
                 [<comment-action> (assoc attrs :class "blog-comment-reply-btn"
                                               :label "Reply" :icon "reply")])
      :cancel  (when adding-comment?
                 [:button.blog-btn.bottomborder
                  {:on-click #(r/dispatch [:blog/cancel-comment parent-path])}
                  "Cancel"]))))

(defc <edit-comment>
  [{:keys [path comment] :as spec}]
  [<comment-action> {:class "blog-comment-edit-btn"
                     :label "Edit comment"
                     :icon "edit"
                     :on-click #(r/dispatch [:blog/edit-comment path comment])}])

(defc <delete-comment> ; Unfinished delete UI; retain for later implementation.
  [{:keys [path] :as spec}]
  [:div "whua"])

(defn- get-id-str [path]
  (reduce (fn [s i]
            (str s "-" i))
          (str "blog-post-" (first path) "-comment")
          (rest path)))

(defc <vote-btn> [{:keys [user active-user path vote] :as spec}]
  (when active-user
    (let [voted @(r/subscribe [:blog/vote path])]
      [:button.blog-btn.blog-comment-vote-btn
       {:class (if (= vote voted)
                 "noborder"
                 (case vote :up "topborder" :down "bottomborder"))
        :disabled @(r/subscribe [:state [:supabase-writes [:vote (str (last path))]]])
        :on-click #(r/dispatch [:blog/comment-vote
                                 user active-user path vote])}
       (case vote :up "+" :down "-")])))

(defc <collapsed-reply-view>
  {:features [[:appear "zoom-y"]]}
  [{:keys [path comments reply-count loading?] :as spec}]
  [:div.blog-comment-reply.flex
   {:style {:cursor "zoom-in"
            :max-height "3rem"}
    :on-click #(if-let [expand! (:expand! spec)] (expand!)
                   (r/dispatch [:blog/expand-comment-thread path true]))}
   [:div.blog-comment-border]
   [:section.blog-comment.blog-comment-collapsed-placeholder
    {:aria-busy (boolean loading?)}
    (if loading? "Loading replies…" (util/pluralize (or reply-count (count comments)) " hidden reply"))]])

(defc <comment-frame> {:features [:props [:appear nil]]} [{:keys [children] :as spec}]
  (into [:div.flex.blog-comment-around] children))

(defc <reply-row>
  "One intrinsic-height row per reply; the same entrance grows its layout box."
  {:features [:props [:appear {:class "reply-height", :settle-offscreen? true}]]}
  [{:keys [expanded? children] :as spec} :- schema/reply-row-spec]
  [:div.blog-comment-reply-row
   {:class (when-not expanded? "collapsed")
    :aria-hidden (not expanded?)
    :inert (not expanded?)}
   (into [:div.blog-comment-reply-body] children)])

(defc <comment-fade> {:features [:props [:appear "opacity"]]} [spec]
  [:div.fade-to-black.bottom {:style {:z-index "0"}}])

(defn- measure-comment-text! [element *full? *truncated? *fade-style]
  (when element
    (let [truncated? (> (util/px-to-rem (.-scrollHeight element)) 24)
          bounds (.getBoundingClientRect element)
          bottom (.-bottom bounds)
          code-bounds (when truncated?
                        (some (fn [code]
                                (let [box (.getBoundingClientRect code)]
                                  (when (< (.-top box) bottom (.-bottom box)) box)))
                              (array-seq (.querySelectorAll element ".code-block"))))]
      (reset! *truncated? truncated?)
      ;; Expansion is an explicit reader choice. Measuring the full body must
      ;; not fold it again, but a short focused comment has nothing to collapse.
      (when-not (true? @*full?) (reset! *full? (when truncated? false)))
      ;; Browser measurements stay at the ref/observer boundary. Reagent owns
      ;; the resulting fade attributes, including code's horizontal margins.
      (reset! *fade-style
        (when code-bounds
          {:left (str (util/px-to-rem (- (.-left code-bounds) (.-left bounds))) "rem")
           :right (str (util/px-to-rem (- (.-right bounds) (.-right code-bounds))) "rem")
           :width "auto"})))))

(def comment-render-spec
  [:merge schema/comment-spec
   [:map
    [:?image-assets [:?map-of :string media-schema/asset]]
    [:?image-readiness [:maybe authored-media/readiness-schema]]]])

(defc <comment-post> "Render a comment and its replies; defer hidden threads until needed."
  {:features [:error-boundary]}
  [{:keys [path visible?] {:keys [id ts user title text score] :as post} :comment :as spec} :- comment-render-spec]
  :let [*full? (r/atom (when (= (str id) (:focused-id spec)) true)) ; nil: not measured, false: truncated, true: expanded
        *truncated? (r/atom false)
        *interacted? (r/atom false)
        *fade-style (r/atom nil)
        *text-element (atom nil)
        capture-height! (fn [element]
                          (reset! *text-element element)
                          (measure-comment-text! element *full? *truncated? *fade-style))]
  (let [[show-expand? show-expand! hide-expand!] (timer/use-delayed-hide 2000)
        *element (r/use-ref nil)
        route @(r/subscribe [:common/route])
        focused? (and (:focused-id spec) (= (str id) (str (:focused-id spec))))
        showing? (timer/use-delayed-visible? (or (nil? visible?) @visible?) 250)
        is-preview? (= [:new-comment] path)
        *expanded? (r/subscribe [:comments/thread-expanded? path])
        *comments (when-not is-preview?
                    (r/subscribe [:comments/visible-thread (first path) (last path)
                                   (boolean (and showing? @*expanded?
                                                 (or (nil? (:reply-count post)) (pos? (:reply-count post))))) path]))]
    (r/use-effect
      (fn []
        (when-let [element (when showing? @*text-element)]
          (when (exists? js/ResizeObserver)
            (let [observer (js/ResizeObserver.
                             #(measure-comment-text! element *full? *truncated? *fade-style))]
              ;; The unclipped Markdown root changes size even when its text
              ;; viewport is capped, including late formatting/font upgrades.
              (.observe observer (or (.querySelector element ".md-rendered") element))
              (fn [] (.disconnect observer))))))
      [showing? text])
    (r/use-layout-effect
      (fn []
        (when (and showing? focused?)
          (reset! *full? true)
          (when (and (.-current *element) (not (get-in route [:data :selection :standalone-comment?])))
            (r/dispatch [:scroll/component-target (str "blog-comment-" id) (:path route)])))
        nil)
      [showing? (boolean focused?) (:path route)])
    (when showing?
      (let [active-user @(r/subscribe [:user/active-user])
            user @(r/subscribe [:user/user user])
            score (or score 0)
            *comments (or *comments (r/atom nil))
            replies? (or (seq @*comments) (pos? (:reply-count post 0)))]
          [:<>
           [<comment-frame> {:appear {:class (or (:appear spec) "zoom-y fast")
                                      ;; The parent owns reply visibility. A slide-behind
                                      ;; root starts outside its clipping container, so
                                      ;; observing that transformed root would deadlock.
                                      :viewport? (nil? visible?)
                                      :restore? (not (:enter? spec))
                                      :remember-key (when (nil? visible?) [:blog/comment path])}
                            :features (if (or focused? (false? (:appear spec))) [[:appear false]] [])
                            :props {:id (when-not (or is-preview? (:quote? spec)) (str "blog-comment-" id))
                                    :class "link-anchor"
                                    :ref (fn [element] (set! (.-current *element) element) nil)
                                    :style {:transition-delay (str (or (:stagger-ms spec) 0) "ms")
                                            "--comment-reveal-duration" (str (or (:motion-ms spec) 180) "ms")
                                            "--comment-reveal-delay" (str (or (:stagger-ms spec) 0) "ms")}}
                            :children [
            [:div.blog-comment-border
             {:style {:cursor (if (and @*expanded? replies?)
                                "zoom-out"
                                (when replies? "zoom-in"))
                      :background-color (:bg-color user)
                      :opacity (if is-preview? 0.5 1.0)}
              :on-click #(when replies?
                           (reset! *interacted? true)
                           (r/dispatch [:blog/expand-comment-thread path
                                         (not @*expanded?)]))}]
            [:section.blog-comment
             {:class (cond @*full? "blog-comment-full"
                           (false? @*full?) "blog-comment-truncated")
              :data-link-trust (name (presentation/link-trust user))
              :style (when is-preview? {:background-color "var(--bg-2-2)"
                                        :opacity 0.8})}

             [:div
              (m/<> :user/avatar user)

              [:div.blog-comment-main
               [:h3.blog-comment-title title]
               [presentation/<posted-by> {:id id :user user :ts ts :score score}]
               (when (not= (:id active-user) (:id user))
                 [:span.blog-comment-vote [<vote-btn> {:user user :active-user active-user :path path :vote :up}]
                                          [<vote-btn> {:user user :active-user active-user :path path :vote :down}]])
               [:div.blog-comment-text
                {:ref capture-height!
                 :style {:filter (when (neg? score)
                                   (str "brightness(calc(1 + "
                                        (max -0.7 (* 0.1 score)) "))"))}}
                [presentation/<comment-markdown> text
                 {:trust (presentation/link-trust user)
                  :image-assets (:image-assets spec)
                  :image-readiness (:image-readiness spec)}]
                (when (false? @*full?)
                  [<comment-fade>
                   {:props {:style @*fade-style
                            :on-mouse-over show-expand!
                            :on-mouse-leave hide-expand!}}])]]

             [:div.blog-comment-actions
               (when-not is-preview?
                 [:<>
                  [<comment-action> {:class "blog-comment-permalink"
                                     :label "Permalink"
                                     :icon "link"
                                     :href @(r/subscribe [:href :blog-post-comment
                                                         {:permalink (str (first path)), :comment-id (str id)}])}]
                  [<comment-action> {:class "blog-comment-thread-link"
                                     :label "Open thread"
                                     :icon "comments"
                                     :href @(r/subscribe [:href :blog-comment {:comment-id (str id)}])}]])
               (when (and (not is-preview?) active-user (= (:id active-user) (:id user)))
                 [<edit-comment> {:path path :comment post}])
               (when (and (not is-preview?) active-user)
                 [<add-comment-btn> {:parent-path path :kind :reply}])]]

             [:div.blog-comment-expansion
              (when (false? @*full?)
                [:<>
                 (when show-expand?
                   [:button.blog-comment-view-full-btn
                    {:aria-label "Show full comment text"
                     :on-click #(reset! *full? true)}
                    [:i.fa.fa-angle-down]])])
              (when (and @*full? @*truncated?)
                [:button.blog-comment-view-full-btn.blog-comment-view-less-btn
                 {:aria-label "Collapse comment text"
                  :on-click #(reset! *full? false)}
                 [:i.fa.fa-angle-up]])]]]}]

           (when (and active-user
                      @(r/subscribe [:comments/adding? path]))
             [<add-comment> {:parent-path path}])

           (when replies? ;replies
             [:div.blog-comment-reply-outer
              [:div.blog-comment-reply
               {:style {"--comment-reveal-duration" (str (comments/reveal-duration-ms (count @*comments)) "ms")}}
               (doall (for [[index post] (map-indexed vector (sort-by :ts (vals @*comments)))]
                        ^{:key (get-id-str (conj path (:id post)))}
                        [<reply-row>
                         {:expanded? @*expanded?
                          :appear {:class "reply-height"
                                   :settle-offscreen? true
                                   :restore? (not (or (:enter? spec) @*interacted?))}
                          :props {:style {"--comment-reveal-delay" (str (min 384 (* 48 index)) "ms")}}
                          :children [[<comment-post> {:path (conj path (:id post))
                                                      :comment post
                                                      :focused-id (:focused-id spec)
                                                      :quote? (boolean (:quote? spec))
                                                      :enter? (or (:enter? spec) @*interacted?)
                                                      :visible? *expanded?
                                                      :appear false}]]}] ))]
              (when (or (not @*expanded?) (nil? @*comments))
                [<collapsed-reply-view> {:path path :comments @*comments :reply-count (:reply-count post)
                                         :expand! (fn [] (reset! *interacted? true)
                                                    (r/dispatch [:blog/expand-comment-thread path true]))
                                         :loading? (and @*expanded? (nil? @*comments))}])])]))))

(defc <comment-focus>
  "Resolve a route intent once; ordinary comment state owns the resulting folds."
  [post-id :- [:maybe c/id]]
  (let [status @(r/subscribe [:comments/focus-state post-id])
        route @(r/subscribe [:common/route])
        id (get-in route [:parameters :path :comment-id])
        *handled (r/use-ref nil)]
    (r/use-effect
      (fn []
        (when (and (:ready? status) (not= (:path route) (.-current *handled)))
          (set! (.-current *handled) (:path route))
          (if (:row status)
            (r/dispatch [:blog/navigate-comment id])
            (r/dispatch [:scroll/component-target "blog-comment-target-status" (:path route)])))
        nil)
      [(:ready? status) (:path route)])
    (when (and (:ready? status) (or (nil? (:row status)) (nil? (:root status))))
      [:p#blog-comment-target-status {:role "alert"}
       "The requested comment could not be found in this thread. "
       [:a {:href @(r/subscribe [:href :blog-comment {:comment-id id}])} "Open it on its own"]])))

(defc <comments-section> "Comments section!"
  {:features [[:appear "zoom-y"]]}
  [{{:keys [id] :as post} :post :as spec} :- schema/post-spec]
  (let [{:keys [records loading? more?]} @(r/subscribe [:comments/root-page id])
        route @(r/subscribe [:common/route])
        focused-id (get-in route [:parameters :path :comment-id])
        comments (->> (vals records) (sort-by (juxt :ts :id)) reverse)]
    [:section.blog-comments
     [:h2.blog-comments-title.bottomborder (str (util/pluralize (count comments) "comment") (when more? "+"))]
     (when focused-id [<comment-focus> id])
     (when (seq comments)
       [:div.blog-comments-inner
        (doall (for [[index comment] (map-indexed vector comments) :let [path [id (:id comment)]]]
                 ^{:key (get-id-str path)}
                 [<comment-post> {:path path :comment comment
                                  :focused-id focused-id
                                  :stagger-ms (min 48 (* 12 index))}]))])
     (when more?
       [:button.blog-btn.blog-load-more
        {:disabled loading? :on-click #(r/dispatch [:blog/load-more-comments id])}
        (if loading? "Loading comments…" "Load more comments")])

     (when @(r/subscribe [:user/active-user])
       [<add-comment-btn> {:parent-path [id] :kind :comment}])
     [<add-comment> {:parent-path [id]}]]))

(defc <submit-comment-btn> [{:keys [parent-path model editing?] :as spec}]
  (let [valid? (pos? (count (:text model)))]
    [:button.blog-btn.noborder
     {:class (when valid? "topborder")
      :disabled (or (not valid?)
                    @(r/subscribe [:comments/uploads-pending? parent-path])
                    @(r/subscribe [:state [:supabase-writes [:comment parent-path]]]))
      :on-click #(when valid?
                   (r/dispatch [:blog/comment-submit parent-path model editing?]))}
     "Submit"]))

(defc <comment-input> "Retained experimental comment field, currently unused."
  [{:keys [parent-path field kind model style ui-name] :as spec}]
  :let [*element (r/atom nil)]
  (let [id (str "blog-adding-comment-" kind)
        height (when (= kind :textarea)
                 {:min-height (when @*element
                                (-> model :text
                                    (str "-")
                                    string/split-lines
                                    count (* 1.15) (+ 2)
                                    (->> (util/em->px id))
                                    (str "px")))})]
    [kind
     {:id id :class "blog-adding-comment-textbox" :type :textbox
      :ref #(when % (reset! *element %))
      :value (get model field)
      :name (or ui-name (name field))
      :placeholder (string/capitalize (or ui-name (name field)))
      :style (merge style height)
      :on-change #(r/dispatch-sync [:form-field [:write-comment parent-path field]
                                    (-> % .-target .-value)])
      :on-blur #(r/dispatch-sync [:form-field [:write-comment parent-path field]
                                  (get model field) :blur])}]))

(defc <comment-editor> [{:keys [parent-path]}]
  (let [account @(r/subscribe [:user/active-user])
        editing @(r/subscribe [:blog/state [:editing-comment parent-path]])
        owner (r/use-memo #(str (random-uuid)) [(:id account) (:id editing)])
        model @(r/subscribe [:form-field [:write-comment parent-path]])
        uploads (filter #(and (= owner (:owner %)) (= :ready (:status %)))
                        (vals @(r/subscribe [:blog/state [:image-uploads]])))
        generations (editor-model/preview-generations uploads)
        readiness (r/use-memo #(preview/readiness owner generations) [owner generations])]
    [:<>
     [<comment-post> {:path [:new-comment]
                      :comment {:user account
                                :title (:title model)
                                :text (or (:text model) "")}
                      :image-assets (preview/assets uploads)
                      :image-readiness readiness}]
     (m/<> {:module :markdown-editor, :view :composer, :load-on :immediate}
            {:owner owner
             :kind :comment
             :form-path [:write-comment parent-path :text]
             :library? false})]))

(defc <add-comment> "Edit or submit a comment with a live preview."
  {:features [:error-boundary]}
  [{:keys [parent-path] :as spec} :- schema/parent-spec]
  :let [*preview? (r/atom false)]
  (when @(r/subscribe [:comments/adding? parent-path])
    (let [*model (r/subscribe [:form-field [:write-comment parent-path]])]
      [:div.blog-comment-reply-outer
       [:div.blog-comment-reply
        [:div.blog-adding-comment
         ;; Retain the alternative hover preview and custom-field experiment.
         #_[:button.blog-btn
            {:on-mouse-over #(reset! *preview? true)
             :on-mouse-leave #(reset! *preview? false)}
            "Preview"]
         #_(if @*preview?
             [<preview-comment> {:model *model :appear "opacity fast"}]
             [<comment-frame>
              {:appear "opacity fast"
               :children
               [[<comment-input> {:parent-path parent-path :field :title :kind :input
                                  :model @*model :style {:background-color "var(--bg-3-2)"}
                                  :ui-name "Title (optional)"}]
                [<comment-input> {:parent-path parent-path :field :text :kind :textarea
                                  :model @*model :ui-name "Comment"}]]}])
         [<comment-editor> {:parent-path parent-path}]
         ;; The experimental field could use :style {:opacity 0.1 :z-index 10}.
         ;; Future shortcut: submit on Alt-Enter.
         [<submit-comment-btn> {:parent-path parent-path :model @*model
                                :editing? @(r/subscribe [:blog/state [:editing-comment parent-path]])}]
         [<add-comment-btn> {:parent-path parent-path :kind :cancel}]]]])))

(defn quoted-comment-query [post-id comment-id]
    {:path-collection [:blog-comments]
     :scoped? true
     :where [[:id :== comment-id] [:parent-post :== post-id]]
     :limit 1})

  (defc <comment-quote>
    "Load a public quoted comment and render its original comment UI with a permalink."
    {:spec-schema [:map [:post-id [:or :string :int]] [:comment-id [:or :string :int]]]
     :depends (fn [{:keys [post-id comment-id]}]
                [{:source :supabase, :query (quoted-comment-query post-id comment-id)}])}
    [{:keys [post-id comment-id]}]
    (let [comments @(r/subscribe [:<-store-q (quoted-comment-query post-id comment-id)])
          comment (first (vals comments))]
      [:blockquote.content-comment-quote
       (if comment
         [<comment-post> {:path [post-id comment-id], :comment comment, :enter? false, :quote? true}]
         [:p "Comment unavailable"])
        [:a {:href @(r/subscribe [:href :blog-post-comment
                                 {:permalink (str post-id), :comment-id (str comment-id)}])}
        "View original comment"]]))

(defc <standalone-comment>
  {:depends (fn [id] [{:source :subscription, :query [:comments/focus-ready? nil id]}])}
  [id :- comments/comment-id]
  (let [{:keys [ready? row parent]} @(r/subscribe [:comments/focus-state nil])
        route @(r/subscribe [:common/route])
        title (when row (or (when-not (string/blank? (:title row)) (:title row))
                            (str "Comment on " (:title parent))))]
    (r/use-layout-effect
      (fn []
        (when row
          (r/dispatch [:common/set-title title])
          (r/dispatch [:scroll/component-target "standalone-comment" (:path route)]))
        nil)
      [(:id row) (:path route) title])
    (if (and row parent)
        [:section#standalone-comment.blog-comments.link-anchor
         [:p [:a {:href @(r/subscribe [:blog/permalink-for-path (or (:permalink parent) (:id parent))])}
              (str "Back to “" (:title parent) "” and all its comments")]]
         [<comment-focus> nil]
         [:div.blog-comments-inner
          [<comment-post> {:path [(:parent-post row) (:id row)]
                           :comment row
                           :focused-id (str (:id row))}]]
         [<add-comment> {:parent-path [(:parent-post row)]}]]
        (if ready?
          [:p {:role "alert"} "Comment not found."]
          [loading/<spinner>]))))
