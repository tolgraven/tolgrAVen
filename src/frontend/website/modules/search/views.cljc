(ns website.modules.search.views
  #?(:cljs (:require [website.react :as r]
                     [website.component.font :as font]
                     [website.component.motion :as motion]
                     [website.component.restore :as restore]
                     [website.render-context :as context]
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
     ;; The owning panel controls layout through presence, including its exit.
     :open? true
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

(m/defc ^:private <suggestion-panel>
  {:features [[:appear {:viewport? false, :restore? false}] :exit]}
  [id suggestions selected]
  [:div.search-autocomplete.search-autocomplete-open
   {:id (str id "-suggestions")
    :role "listbox"
    :aria-label "Search suggestions"}
   [:div.search-autocomplete__content
    (for [[index suggestion] (map-indexed vector suggestions)]
      ^{:key suggestion}
      [:button.search-autocomplete-item.noborder
       {:type "button"
        :id (str id "-suggestions-" index)
        :role "option"
        :tab-index -1
        :aria-selected (= selected index)
        :on-mouse-down #(.preventDefault %)
        :on-click #(r/dispatch [:search/query suggestion])}
       suggestion])]])

(m/defc <suggestions>
  {:features [:presence]}
  [{:keys [id open?]}]
  (let [suggestions @(r/subscribe [:search/suggestions])
        selected @(r/subscribe [:search/selected])
        hydrating? (r/use-context restore/hydrating-context)
        document? (or context/*server?* hydrating? (restore/initial-hydration?))]
    [:<>
     (when (and open? (seq suggestions))
       ^{:key :search-suggestions}
       [<suggestion-panel> {:features [[:appear {:viewport? false, :restore? document?}]]}
        id suggestions selected])]))

(m/defc <instant-results> [open?]
  (let [query @(r/subscribe [:search/query])
        response @(r/subscribe [:search/response])
        pending? @(r/subscribe [:search/pending?])
        generation @(r/subscribe [:search/get [:generation]])
        error @(r/subscribe [:search/error])]
    (when (and open? (not (string/blank? query)))
      [:div.search-instant-results
       {:aria-busy (boolean pending?)
        :on-click (fn [event]
                    (when (and (zero? (.-button event))
                               (not (or (.-ctrlKey event) (.-metaKey event)
                                        (.-shiftKey event) (.-altKey event)))
                               (some-> (.-target event) (.closest "a[href]")))
                      (r/dispatch [:search/state [:open?] false])))}
       [:div {:role "status", :aria-live "polite"}
        (cond error error
              pending? "Searching…"
              response (str (:found response) (if (= 1 (:found response)) " result" " results")))]
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

(m/defc ^:private <panel>
  {:features [[:appear {:viewport? false, :restore? false}] :exit]}
  []
  (let [open? @(r/subscribe [:search/open?])]
    [:section.search-ui {:class (when open? "search-ui-open")}
     [:div.search-ui__content
      [<box> {:id "search-input", :open? open?}]
      ;; Retain the list's intrinsic height while the whole panel exits.
      [<suggestions> {:id "search-input", :open? true}]]]))

(m/defc <ui> "Search public content through the shared search boundary."
  {:features [:error-boundary :presence]}
  [& _]
  (let [open? @(r/subscribe [:search/open?])
        hydrating? (r/use-context restore/hydrating-context)
        document? (or context/*server?* hydrating? (restore/initial-hydration?))]
    [:<>
     (when open?
       ^{:key :search-panel}
       [<panel> {:features [[:appear {:viewport? false, :restore? document?}]]}])]))

(m/defc ^:private <results-panel>
  {:features [[:appear {:viewport? false, :restore? false}] :exit]}
  []
  [:div.search-results-panel
   [:div.search-results-panel__content
    [<instant-results> true]]])

(m/defc <results> "Header results remain outside the sticky controls."
  {:features [:error-boundary :presence]}
  []
  (let [hydrating? (r/use-context restore/hydrating-context)
        document? (or context/*server?* hydrating? (restore/initial-hydration?))]
    [:<>
     (when @(r/subscribe [:search/open?])
       ^{:key :search-results}
       [<results-panel> {:features [[:appear {:viewport? false, :restore? document?}]]}])]))

(m/defc <inline> "Always-visible search using the same query and result state."
  {:features [:error-boundary]}
  []
  (let [id (r/use-id)]
    [:section.content-search
     [<box> {:id id, :open? true, :inline? true}]
     [<suggestions> {:id id, :open? true}]
     [<instant-results> true]]))

))
