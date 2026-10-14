(ns website.modules.search.views
  #?(:cljs (:require [website.react :as r]
                     [website.component.font :as font]
                     [website.component.motion :as motion]
                     [website.modules.styled-input.views :as styled-input]
                     [website.modules.search.presentation :as presentation]
                     [website.components.image :as img]
                     [website.util :as util]
                     [clojure.string :as string]))
  #?(:cljs (:require-macros [website.macros :as m])))

#?(:cljs
   (do

(m/defc <button> "Search button, press to show input field."
  []
  (let [open? @(r/subscribe [:search/open?])]
    [:button.search-ui-btn.noborder.nomargin
     {:name "Search"
      :title "Search site"
      :aria-expanded open?
      :on-click #(r/dispatch [:search/state [:open?] (not open?)])}
     [img/<picture> {:src "svg/search-ico.svg"
                     :alt "Search"
                     :style {:width "1.2em"
                             :height "1.2em"
                             :filter "var(--light-to-dark)"}}]]))

(m/defc <completion> [query suggestion _height]
  (when (and suggestion (string/starts-with? (string/lower-case suggestion)
                                           (string/lower-case query)))
    [:span.styled-input-autocomplete (subs suggestion (count query))]))

(m/defc <query-text>
  {:features [[:font (fn [_ open?] (when open? font/fira))]]}
  [text open?]
  [:span.styled-query-text {:style {:white-space :pre-wrap}} text])

(defn- key-down! [event suggestions selected inline?]
  (when-not (.-isComposing (.-nativeEvent event))
    (case (.-key event)
      "ArrowDown" (when (seq suggestions)
                    (.preventDefault event)
                    (r/dispatch [:search/select 1]))
      "ArrowUp" (when (seq suggestions)
                  (.preventDefault event)
                  (r/dispatch [:search/select -1]))
      "Tab" (when (and (not (.-shiftKey event)) (seq suggestions))
              (.preventDefault event)
              (r/dispatch [:search/query (get suggestions (max 0 selected))]))
      "Enter" (do (.preventDefault event)
                   (r/dispatch (if (and (<= 0 selected) (< selected (count suggestions)))
                                 [:search/query (get suggestions selected)]
                                 [:search/submit])))
      "Escape" (when-not inline?
                   (.preventDefault event)
                   (.stopPropagation event)
                   (r/dispatch [:search/state [:open?] false]))
      nil)))

(m/defc <box> "Controlled query and keyboard completion using the existing styled input."
  [{:keys [id open? inline?]}]
  (let [query (r/subscribe [:search/query])
        suggestions @(r/subscribe [:search/suggestions])
        selected @(r/subscribe [:search/selected])
        selected' (or selected -1)
        list-id (str id "-suggestions")]
    (r/use-effect
      (fn []
        (when (and open? (not inline?) (exists? js/document))
          (let [input (util/elem-by-id id)
                stop! (motion/next-frame!
                        #(when input (.focus input #js {:preventScroll true})))]
            (fn []
              (stop!)
              (when (identical? input (.-activeElement js/document)) (.blur input))))))
      [id open? inline?])
    [styled-input/<input-text-styled>
     :id id
     :model query
     :controlled? true
     :animate? false
     :text-fn <query-text>
     :open? open?
     :height (if inline? "2.5em" "2em")
     :suggestions [(get suggestions (max 0 selected'))]
     :completion-fn <completion>
     :input-attrs {:role "combobox"
                   :aria-label "Search site"
                   :aria-autocomplete "both"
                   :aria-expanded (boolean (and open? (seq suggestions)))
                   :aria-controls list-id
                   :aria-activedescendant (when (and open? (<= 0 selected') (seq suggestions))
                                            (str list-id "-" selected'))
                   :max-length 200}
     ;; Controlled inputs must accept the value before React restores the DOM;
     ;; match the shared input adapter's synchronous event boundary. Transport
     ;; remains debounced by the registered query event.
     :on-change #(r/dispatch-sync [:search/query %])
     :on-key-down #(key-down! % suggestions selected' inline?)]))

(m/defc <suggestions> [{:keys [id open?]}]
  (let [suggestions @(r/subscribe [:search/suggestions])
        selected @(r/subscribe [:search/selected])
        list-id (str id "-suggestions")]
    [:div.search-autocomplete
     {:id list-id
      :role "listbox"
      :aria-label "Search suggestions"
      :class (when (and open? (seq suggestions)) "search-autocomplete-open")}
     (when open?
       (for [[index suggestion] (map-indexed vector suggestions)]
         ^{:key suggestion}
         [:button.search-autocomplete-item.noborder
          {:type "button"
           :id (str list-id "-" index)
           :role "option"
           :tab-index -1
           :aria-selected (= selected index)
           :on-mouse-down #(.preventDefault %)
           :on-click #(r/dispatch [:search/query suggestion])}
          suggestion]))]))

(m/defc <instant-results> [open?]
  (let [query @(r/subscribe [:search/query])
        response @(r/subscribe [:search/response])
        pending? @(r/subscribe [:search/pending?])
        generation @(r/subscribe [:search/get [:generation]])
        error @(r/subscribe [:search/error])]
    (when (and open? (not (string/blank? query)))
      [:div.search-instant-results
       {:aria-busy (boolean pending?)}
       [:div {:role "status", :aria-live "polite"}
        (cond error error
              pending? "Searching…"
              response (str (:found response) " results"))]
       (when error
         [:button {:type "button", :on-click #(r/dispatch [:search/submit])} "Retry"])
       (for [{:keys [document preview] :as hit} (:hits response)]
         (with-meta
           (m/<> {:module (keyword (namespace preview))
                  :view (keyword (name preview))
                  :load-on :immediate
                  :<loading> [presentation/<fallback> hit]}
                  hit)
           {:key (:id document)}))
       (when (and response (not pending?) (not error) (empty? (:hits response)))
         [:p "No matching content."])
       (when (and response (not pending?) (< 1 (:page response)))
         [:button {:type "button"
                   :on-click #(r/dispatch [:search/request generation query (dec (:page response))])}
          "Previous results"])
       (when (and response (not pending?)
                  (< (* 12 (:page response)) (:found response)))
         [:button {:type "button"
                   :on-click #(r/dispatch [:search/request generation query (inc (:page response))])}
          "Next results"])])))

(m/defc <full-results> "Preserved full-results experiment."
  [collection query])

(m/defc <ui> "Search public content through the shared search boundary."
  {:features [:error-boundary]}
  [& _]
  (let [open? @(r/subscribe [:search/open?])]
    [:section.search-ui {:class (when open? "search-ui-open")}
     [<box> {:id "search-input", :open? open?}]
     [<suggestions> {:id "search-input", :open? open?}]
     [<instant-results> open?]]))

(m/defc <inline> "Always-visible search using the same query and result state."
  {:features [:error-boundary]}
  []
  (let [id (r/use-id)]
    [:section.content-search
     [<box> {:id id, :open? true, :inline? true}]
     [<suggestions> {:id id, :open? true}]
     [<instant-results> true]]))

))
