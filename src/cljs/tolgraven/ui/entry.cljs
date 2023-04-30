(ns tolgraven.ui.entry
  (:require
   [clojure.string :as string]
   [tolgraven.ui :as ui]
   [reagent.core :as r]
   [re-frame.core :as rf]))

(def char-width 0.61225)

(defn- completion
  [query suggestion height]
  (when-not (string/blank? (:match suggestion))
    (let [words (-> (str #_(-> (get suggestion :query "")
                         (string/replace  #"\w" " ")) ; get as many spaces as there were letters
                         (get suggestion :rest ""))
                    (string/replace #"\n.*" "")
                    (string/replace #"^(.{0,40})(.*)" "$1...")
                    (string/replace query "")
                    seq)
          [char1 others] [(first words) (rest words)]]
      [:span.search-input-autocomplete
       {:style {:white-space :pre-wrap
                :display :inline-flex}}
       [:span.first-char char1]
       (for [letter others] ; causes issues with spacing? nice lil zoom effect though, figure out.
         [ui/appear-anon "slide-in faster"
          [:span
           {:style {:min-height height}}
           letter]])])))

(defn box "Search input field"
  [model suggestions-query on-change
   & {:as args :keys [on-enter placeholder width height open? opts]}]
 (let [internal-model (r/atom (or @model ""))
       div-ref (r/atom nil)
       caret (r/atom 0)
       selection-end (r/atom 0)
       set-caret (fn [target]
                   (reset! caret (.-selectionStart target))
                   (reset! selection-end (.-selectionEnd target))) ]
   (fn [model suggestions-query on-change &
        {:keys [on-enter placeholder width height open? opts]
         :or {height "2em" on-enter on-change }}]
     (let [suggestions @(rf/subscribe suggestions-query)
           suggestion (first suggestions)
           caret-pos (str (* char-width @caret) "em")
           selection-len (* char-width (abs (- @caret @selection-end)))
           caret-watch (add-watch internal-model :caret-watch
                                  (fn [rf k old new]
                                    (reset! caret (inc (count new)))
                                    (reset! selection-end (inc (count new)))))
           caret-height (* 1.6 (max 0.(- 1.0 (* 0.03 selection-len) )))]
   [:div.search-input-container
    {:class (when-not open? "closed")}
    
    [:div.search-query-visible
     {:style {:height height }}
     
     [:label.search-caret.blinking.nomargin.nopadding
      {:style {:position :absolute
               :width (str (max char-width selection-len) "em")
               :height (str caret-height "em")
               :left caret-pos :top (str (- (/ (- 1.6 caret-height #_"-0.1em") 2) 0.15) "em")}}
       "_"]
     [:label.search-caret-under.nomargin.nopadding
      {:style {:position :absolute
               :left caret-pos :top "0.1em"
               :animation (when-not (zero? selection-len)
                            "unset")}}
      "_"]
     
     (when-not (string/blank? @internal-model)
       [:span {:style {:white-space :pre-wrap
                       :display :inline-flex}}
        (for [letter @internal-model] ; causes issues with spacing? nice lil zoom effect though, figure out.
          [ui/appear-anon "zoom fast"
           [:span.search-letter letter]])])
     
     [completion @internal-model suggestion height]]
     
     [:input#search-input.search-input ;problem if multiple search boxes on same page tho
      {:type "search"
       :incremental true
       :style {:opacity 0
               :width width ;:min-width width :max-width width
               ; :height height 
               :min-height height
               :max-height height
               :padding (when (or (zero? width) (zero? height)) 0)
               :border (when (or (zero? width) (zero? height)) 0)}
       :placeholder (or placeholder "Search") ; might want "Search for..." like
       :autoComplete "off"
       :max 40
       :value      @internal-model
       :ref         #(when % (reset! div-ref %))
       :on-change (fn [e] ; XXX needs debounce I guess
                    (let [new-val (-> e .-target .-value)]
                      (reset! internal-model new-val)
                      (on-change))
                    (set-caret (.-target e)))
       :on-search (fn [e] ; this is da debounce! apparently recommended against. also not working anyways hahah
                    (let [new-val (-> e .-target .-value)]
                      (on-enter)))
       
       :on-key-down (fn [e] (set-caret (.-target e)))
       :on-click (fn [e] (set-caret (.-target e)))
       :on-touch-start (fn [e] (set-caret (.-target e)))
       :on-touch-move (fn [e]
                        (reset! selection-end (-> e .-target .-selectionEnd))) ; actually just set selection I suppose
       :on-touch-end (fn [e]
                       (reset! selection-end (-> e .-target .-selectionEnd)))
       ; how handle moving caret using mobile keyboard tap and hold and swipe on spacebar?
       :on-key-up (fn [e]
                    (case (.-key e)
                      "Enter" (when (not= "" @model)
                                )
                      "Escape" (do (.stopPropagation e) ; can't seem to stop it from blanking query hmm
                                   (.preventDefault e))
                      true)
                    (fn [e] (set-caret (.-target e))))}]
     
     [:span.search-input-info "BETA"]]))))
