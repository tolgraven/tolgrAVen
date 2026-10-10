(ns tolgraven.modules.styled-input.views
  (:require [tolgraven.component]
            [tolgraven.component.font :as font]
            [reagent.core :as r]
            [tolgraven.macros :as m :include-macros true]
            [tolgraven.components.ui :as ui]
            [clojure.string :as string]))

(m/defc ^:private <styled-letter>
  {:features [[:appear "zoom fast"]]}
  [letter]
  [:div [:span.styled-letter letter]])

(m/defc <input-text-styled> "Custom text field with individual elements for each letter, and styled caret"
  {:features [[:font (fn [& {:keys [open?] :or {open? true}}]
                       (when open? font/fira))]]}
  [& {:as args :keys [model completion-fn]}]
 (let [internal-model (r/atom (or @model ""))
       char-width 0.61225
       div-ref (r/atom nil)
       caret (r/atom 0)
       selection-end (r/atom 0)
       set-caret (fn [target]
                   (reset! caret (.-selectionStart target))
                   (reset! selection-end (.-selectionEnd target))) ]
   (fn [& {:keys [on-enter placeholder
                  width height open?
                  suggestions
                  id class
                  on-change on-enter on-esc]
           :or {height "2em"
                open? true}}]
     (let [suggestion-str (string/join " " (:match suggestions))
           suggestion (first suggestions)
           caret-pos (str (* char-width @caret) "em")
           selection-len (* char-width (abs (- @caret @selection-end)))
           caret-height (* 1.6 (max 0.(- 1.0 (* 0.03 selection-len))))
           output [ui/<md->div> @internal-model]]
   [:div.styled-input-container
    {:class (when-not open? "closed")}

    [:div.styled-query-visible
     {:style {:height height }}

     [:label.styled-caret.nomargin.nopadding
      {:id    "styled-caret"
       :for   "styled-input"
       :style {:position :absolute
               :width (str (max char-width selection-len) "em")
               :height (str caret-height "em")
               :left caret-pos
               :top (str (- (/ (- 1.6 caret-height #_"-0.1em") 2) 0.15) "em")}}
       "_"]
     [:label.styled-caret-under.nomargin.nopadding
      {:id    "styled-caret-under"
       :for   "styled-input"
       :style {:position :absolute
               :left caret-pos
               :top "0.1em"
               :animation (when-not (zero? selection-len)
                            "unset")}}
      "_"]

     (when-not (string/blank? @internal-model)
       [:span {:style {:white-space :pre-wrap
                       :display :inline-flex}}
        (for [[i letter] (map-indexed vector @internal-model)] ; causes issues with spacing? nice lil zoom effect though, figure out.
          ^{:key i}
          [<styled-letter> letter])])

     (when completion-fn
       [completion-fn @internal-model suggestion height])]

     [:input.styled-input ;problem if multiple search boxes on same page tho
      {:type "textarea"
       :id   (or id "styled-input")
       :class class
       :style {:opacity 0
               :width width ;:min-width width :max-width width
               ; :height height
               :min-height height
               :max-height height
               :padding (when (or (zero? width) (zero? height)) 0)
               :border (when (or (zero? width) (zero? height)) 0)}
       :placeholder (or placeholder "🔎")
       :autoComplete "off"
       :max 40
       :value      @internal-model
       :ref         #(when % (reset! div-ref %))
       :on-change (fn [e] ; XXX needs debounce I guess
                    (let [new-val (-> e .-target .-value)]
                      (reset! internal-model new-val)
                      (and (some? on-change) (on-change new-val)))
                    (set-caret (.-target e)))

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
                      "Enter" (when (and (not= "" @internal-model)
                                         (some? on-enter))
                                (on-enter @internal-model))
                      "Escape" (do (.stopPropagation e) ; can't seem to stop it from blanking query hmm
                                   (.preventDefault e)
                                   (and (some? on-esc) (on-esc @internal-model)))
                      true)
                    (fn [e] (set-caret (.-target e))))}]]))))
