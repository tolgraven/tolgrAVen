(ns website.post-editor-visual-fixture
  "Full native posting page with local reads and no durable writes."
  (:require [website.react :as r]
            [website.modules.blog-editor.views :as views]
            [website.modules.blog-editor.draft-schema :as draft-schema]
            [website.media.schema :as media-schema]
            [website.validation :as validation]))

(def form
  {:title "Native editor draft"
   :tags "clojure layout"
   :text "## Draft section\n\nNative **rich text**, a [link](https://example.invalid/) and `inline code`.\n\n- First item\n- Second item\n"
   :image-assets {}})

(def entries
  {"visual-draft-first" {:id "visual-draft-first", :form form}
   "visual-draft-second" {:id "visual-draft-second"
                          :form (assoc form
                                       :title "Selected native draft"
                                       :text "## Another draft\n\nRestored through the native draft chooser.\n")}})

(def asset
  {:id "visual-library-photo"
   :src "/img/tolgrav-square.png"
   :width 106
   :height 106
   :renditions {:original {:src "/img/tolgrav-square.png"
                           :width 106
                           :height 106
                           :bytes 10986
                           :mime "image/png"}
                :webp [{:src "/img/tolgrav-square-80w.webp"
                        :width 80
                        :height 80
                        :bytes 1106
                        :mime "image/webp"}]
                :avif [{:src "/img/tolgrav-square-80w.avif"
                        :width 80
                        :height 80
                        :bytes 1171
                        :mime "image/avif"}]}})

(defonce *effects (atom []))

(defn- reject-write! [operation]
  (swap! *effects conj {:operation :blocked-write, :kind operation})
  (throw (js/Error. "Post editor visual fixtures do not write or publish")))

(defn install! [profile]
  (let [collection {:owner-id (:id profile), :entries entries}
        library {:assets [asset], :next-offset nil}]
    (when (or (validation/explain draft-schema/form form)
              (validation/explain draft-schema/collection collection)
              (validation/explain media-schema/library library))
      (throw (js/Error. "Invalid native post editor fixture data")))
    (r/dispatch-sync [:state [:supabase-init] :ready])
    (r/dispatch-sync [:state [:active-user] profile])
    (r/dispatch-sync [:form-field [:post-blog] form])
    ;; The real draft owner, result events, chooser, document switching and
    ;; editor lifetime remain intact. Replace only private storage I/O; these
    ;; disposable documents neither track nor flush the user's browser drafts.
    (r/reg-fx :blog-editor/read-drafts
      (fn [{:keys [id owner account token before restore? document-id]}]
        (swap! *effects conj {:operation :draft-read})
        (r/dispatch [:blog/drafts-read owner account token id before restore? document-id nil collection])))
    (doseq [[effect operation] [[:blog-editor/track-drafts :draft-attach]
                                [:blog-editor/untrack-drafts :draft-detach]]]
      (r/reg-fx effect (fn [_] (swap! *effects conj {:operation operation}))))
    (r/reg-fx :blog-editor/commit-drafts (fn [_] (reject-write! :private-draft)))
    (r/reg-fx :supabase/request
      (fn [{:keys [method uri on-success]}]
        (swap! *effects conj {:method method, :uri uri})
        (if (= :get method)
          (if (= "/api/supabase/media?offset=0&limit=24" uri)
            (r/dispatch (conj on-success library))
            (throw (js/Error. "Unexpected post editor fixture read")))
          (reject-write! :provider-request))))
    (r/reg-fx :http-xhrio
      (fn [{:keys [method]}]
        (if (= :get method)
          (throw (js/Error. "Unexpected HTTP read in post editor visual fixture"))
          (reject-write! :http))))))

(defn ^:export state []
  {:form @(r/sub [:get :state :form-field :post-blog])
   :draft (select-keys @(r/sub [:get :state :blog-editor :draft]) [:status :named? :document-id])
   :editors (count (filter #(= :post (:kind %))
                          (vals @(r/sub [:get :state :blog-editor :editors]))))
   :draft-reads (count (filter #(= :draft-read (:operation %)) @*effects))
   :library-reads (count (filter #(= :get (:method %)) @*effects))
   :writes (count (filter #(= :blocked-write (:operation %)) @*effects))})

(defn <fixture> []
  [:main#main.main-content.perspective-top [views/<page>]])
