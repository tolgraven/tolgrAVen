(ns website.page-layout
  "Portable CMS composition. Only known views and bounded layout values cross this boundary.")

(def components
  [{:id "page-index", :module :home}
   {:id "intro", :module :home}
   {:id "services", :module :home}
   {:id "moneyshot", :module :home}
   {:id "story", :module :home}
   {:id "gallery", :module :home}
   {:id "soundcloud", :module :home}
   {:id "interlude", :module :home}
   {:id "strava", :module :strava}
   {:id "instagram", :module :instagram}
   {:id "github", :module :github}
   {:id "codex-profile", :module :codex-profile}
   {:id "gpt", :module :gpt}
   {:id "chat", :module :chat}])

(defn valid-article? [article]
  (let [text? #(and (string? %) (<= (count %) 65536))
        media? #(and (map? %) (string? (:src %))
                     (every? (fn [key] (or (not (contains? % key)) (text? (get % key)))) [:alt :title]))
        heading (:heading article)]
    (and (map? article) (text? (:title article)) (text? (:text article))
         (map? heading)
         (every? (fn [key] (or (not (contains? heading key)) (text? (get heading key)))) [:title :caption :target :tint])
         (or (not (contains? heading :bg)) (media? (:bg heading)))
         (vector? (:images article)) (<= (count (:images article)) 100)
         (every? (fn [image]
                   (and (vector? image) (<= 2 (count image) 4)
                        (text? (first image)) (media? (second image))
                        (every? text? (drop 2 image)))) (:images article)))))

(def breakpoints ["mobile" "tablet" "small-monitor" "large-monitor"])
(def columns [:map-of (into [:enum] (map keyword breakpoints)) [:int {:min 1, :max 12}]])
(def section
  [:map
   [:identity [:re #"^[a-z][a-z0-9-]{0,79}$"]]
   [:component [:re #"^[a-z][a-z0-9-]{0,79}$"]]
   [:?label [:string {:max 120}]]
   [:?wide :boolean]
   [:?outline :boolean]
   [:?span [:int {:min 1, :max 12}]]
   [:?region [:enum "main" "aside"]]
   [:?index [:int {:min 0, :max 100}]]
   [:?content [:and :map [:fn valid-article?]]]])
(def page
  [:map
   [:identity [:re #"^[a-z][a-z0-9-]{0,79}$"]]
   [:?path [:re #"^/(?:[a-z0-9-]+/?)*$"]]
   [:?wide :boolean]
   [:?outline :boolean]
   [:?columns columns]
   [:sections [:vector {:max 100} section]]])
(def schema [:vector {:max 100} page])

(defn unique-identities? [pages]
  (and (= (count pages) (count (distinct (map :identity pages))))
       (let [paths (keep :path pages)] (= (count paths) (count (distinct paths))))
       (every? (fn [{:keys [sections]}]
                 (= (count sections) (count (distinct (map :identity sections))))) pages)))

(defn available-components
  ([settings] (filterv #(not= false (get-in settings [:modules (:module %) :enabled]))
                       (or (:layout-components settings) components)))
  ([settings declarations]
   (let [direct (filter #(= :home (:module %)) components)
         modules (for [{:keys [id layout-view?]} declarations :when layout-view?]
                   {:id (name id), :module id})]
     (available-components (assoc settings :layout-components (vec (concat direct modules)))))))

(defn responsive-value
  "Mobile-first inheritance, also useful with breaking-point's current screen."
  [values screen fallback]
  (reduce (fn [value breakpoint] (get values (keyword breakpoint) value)) fallback
          (take (inc (or (first (keep-indexed #(when (= %2 screen) %1) breakpoints)) 0)) breakpoints)))

(def defaults
  [{:identity "home"
    :sections (mapv (fn [[identity component index]]
                      (cond-> {:identity identity, :component component}
                        (some? index) (assoc :index index)))
                    [["intro" "intro"] ["interlude-0" "interlude" 0]
                     ["services" "services"] ["interlude-1" "interlude" 1]
                     ["moneyshot" "moneyshot"] ["story" "story"]
                     ["interlude-2" "interlude" 2] ["strava" "strava"]
                     ["soundcloud" "soundcloud"] ["instagram" "instagram"]
                     ["gallery" "gallery"] ["github" "github"]
                     ["codex-profile" "codex-profile"] ["gpt" "gpt"] ["chat" "chat"]])}
   {:identity "experiments"
    :path "/experiments/layout"
    :wide true
    :outline false
    :columns {:mobile 1, :tablet 2, :small-monitor 3}
    :sections [{:identity "experiment-story"
                :component "story"
                :label "An independent article"
                :span 2
                :content {:heading {:title "A different perspective", :caption "Composed in Strapi"}
                          :title "A page assembled from components"
                          :text "This article belongs to the experiments layout. Editing it does not change the home page story."
                          :images []}}
               {:identity "experiment-index", :component "page-index", :region "aside"}
               {:identity "experiment-github", :component "github", :span 2}
               {:identity "experiment-strava", :component "strava", :region "aside"}]}])

(defn find-page [pages identity]
  (first (filter #(= identity (:identity %)) pages)))

(defn selected-pages [settings]
  (let [available (set (map :id (available-components settings)))]
    (mapv #(update % :sections (fn [sections] (filterv (comp available :component) sections)))
          (or (:layouts settings) defaults))))

(defn- bounded-integer? [value minimum maximum]
  (and (integer? value) (<= minimum value maximum)))

(defn- optional-fields? [value validators]
  (and (map? value)
       (every? (set (keys validators)) (keys value))
       (every? (fn [[field valid?]] (or (not (contains? value field)) (valid? (get value field)))) validators)))

(defn- identifier? [value]
  (and (string? value) (boolean (re-matches #"[a-z][a-z0-9-]{0,79}" value))))

(defn valid-section? [value]
  (and (identifier? (:identity value))
       (identifier? (:component value))
       (optional-fields? value
         {:identity identifier?
          :component identifier?
          :label #(and (string? %) (<= (count %) 120))
          :wide boolean?
          :outline boolean?
          :span #(bounded-integer? % 1 12)
          :region #{"main" "aside"}
          :index #(and (= "interlude" (:component value)) (bounded-integer? % 0 100))
          :content #(and (= "story" (:component value)) (valid-article? %))})))

(defn valid-pages? [pages]
  (and (vector? pages) (<= (count pages) 100) (unique-identities? pages)
       (every? (fn [page]
                 (and (identifier? (:identity page)) (vector? (:sections page))
                      (optional-fields? page
                        {:identity identifier?
                         :path #(and (string? %) (<= (count %) 200)
                                     (boolean (re-matches #"/(?:[a-z0-9-]+/?)*" %)))
                         :wide boolean?
                         :outline boolean?
                         :columns #(and (map? %) (every? (set (map keyword breakpoints)) (keys %))
                                        (every? (fn [n] (bounded-integer? n 1 12)) (vals %)))
                         :sections #(and (vector? %) (<= (count %) 100) (every? valid-section? %))}))) pages)))

(def reserved-path #"/(?:$|about(?:/|$).*|services(?:/|$).*|hire(?:/|$).*|blog(?:/|$).*|cv(?:/|$).*|docs(?:/|$).*|test(?:/|$).*|neovim(?:/|$).*|api(?:/|$).*|log(?:/|$).*|client-oauth(?:/|$).*)")

(defn validate! [pages]
  (when-not (and (valid-pages? pages)
                 (not-any? #(and (:path %) (re-matches reserved-path (:path %))) pages))
    (throw (ex-info "Invalid page layouts: use unique identities/paths, known components and bounded grid settings" {})))
  pages)

(defn validate-components! [pages choices]
  (let [known (set (map :id choices))]
    (doseq [page pages, section (:sections page)]
      (when-not (known (:component section))
        (throw (ex-info "Page layout names an unavailable component" {:component (:component section)})))))
  pages)

(defn routes [pages]
  (mapv (fn [{:keys [identity path]}]
          [path {:name (keyword (str "layout-" identity)), :layout identity, :preload-images []}])
        (filter :path pages)))
