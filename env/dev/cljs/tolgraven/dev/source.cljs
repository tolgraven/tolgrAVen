(ns tolgraven.dev.source
  "Source documentation uses normal component data bindings and the module loader."
  (:require [clojure.string :as string]
            [tolgraven.dev.source-links :as links]
            [tolgraven.react :as rf]
            [tolgraven.macros :refer-macros [defc defpage]]
            [tolgraven.component :as component]
            [tolgraven.component.instrumentation]
            [tolgraven.component.data :as data]
            [tolgraven.diagnostics.source-contract :as contract]
            [tolgraven.components.code-block :as code]))

(def catalog-path links/catalog-path)
(def catalog-resource links/catalog-resource)
(def source-url links/source-url)
(def resolve-file links/resolve-file)
(defn file-resource [file]
  {:source :url
   :url (str "/api/dev/source?file=" (js/encodeURIComponent file))})
(rf/reg-sub-raw :dev-source/file
  (fn [_ [_ file]]
    (rf/make-reaction #(:value (data/snapshot (file-resource file))))))

(rf/reg-event-fx :dev-source/reveal-line
  (fn [{:keys [db]} [_ file target]]
    (when (and (= :source (get-in db [:common/route :data :page]))
               (= file (get-in db [:common/route :path-params :file])))
      ;; Use the page's owned positioning adapter. A direct scroll from a line
      ;; ref would compete with navigation's still-settling restore position.
      {:dispatch [:page/ready target (get-in db [:page/commit :completion])]})))

(defn tree [entries]
  (reduce (fn [result {:keys [path] :as entry}]
            (assoc-in result (string/split path #"/") entry)) {} entries))

(defc <tree> [nodes current prefix]
  [:ul.dev-source__tree
   (for [[name node] (sort-by key nodes)
         :let [path (conj prefix name)]
         :when (not= name :path)]
     ^{:key (string/join "/" path)}
     [:li
      (if (:path node)
        [:a {:href (source-url (:path node) nil)
             :aria-current (when (= current (:path node)) "page")} name]
        [:details {:open (string/starts-with? (or current "") (str (string/join "/" path) "/"))}
         [:summary name]
         [<tree> node current path]])])])

(defc <listing>
  {:depends (fn [file] [(file-resource file)])
   :loading-tag :section.dev-source__listing}
  [file :- contract/file-path]
  (let [*revealed (rf/use-ref nil)
        source @(rf/subscribe [:dev-source/file file])]
    [:section.dev-source__listing
     [:h2 (:path source)]
     [:p (:namespace source)]
     [code/<code-block> (:content source)
      {:language (:language source)
       :line-numbers? true
       :line-props (fn [line]
                     (clj->js {:id (str "L" line)
                               :className (when (= (str "#L" line) (.-hash js/location)) "dev-source__line--target")
                               :ref (fn [element]
                                      (let [target (str "#L" line)]
                                        (when (and element (= target (.-hash js/location))
                                                   (not= [file target] (.-current *revealed)))
                                          (set! (.-current *revealed) [file target])
                                          (rf/dispatch [:dev-source/reveal-line file (str "L" line)]))))}))}]]))

(defpage <page>
  {:depends [catalog-resource]
   :loading-tag :section.dev-source}
  []
  :let [*filter (<sub :comp [:filter] {:initial ""})]
  (let [catalog (:value @(rf/subscribe [:component-data/installed catalog-path]))
        route @(rf/subscribe [:common/route])
        file (or (get-in route [:path-params :file]) (:path (first catalog)))
        entries (filterv #(string/includes? (string/lower-case (:path %))
                                           (string/lower-case @*filter)) catalog)]
    [:section.dev-source
     [:aside
      [:h1 "Source"]
      [:label "Find a file" [:input {:type "search"
                                     :value @*filter
                                     :on-change #(>reset *filter (.. % -target -value))}]]
      [<tree> (tree entries) file []]]
     (when file ^{:key file} [<listing> file])]))
