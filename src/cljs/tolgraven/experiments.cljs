(ns tolgraven.experiments
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [cljsjs.codemirror :as codemirror]
            [cljsjs.codemirror.keymap.vim]
            [cljsjs.codemirror.mode.markdown]
            [react-transition-group :as rtg]))

; (def css-trans-group (r/adapt-react-class rtg/CSSTransitionGroup))

;       (.add "elem-enter"))))
(defn transition-group "Might be good exercise in React to try implement in cljs?
                        Though understanding what causing issues / moving away from cljsjs would
                        likely be even better exercise in productivity. Q is what sought"
  [attrs & components])

(defn parallax []
  [:<>
   (let [elems (r/atom [1 2 3])]
   ; (let [elems (or @(rf/subscribe [:state :elems]) [1 2 3])]
     [:div.parallax-ui ;.fullwide
      [:div "Pure CSS parallax scroll demo #3 by Keith Clark -> tolgraven"]
      [:> rtg/TransitionGroup
       [:div.elem
        (doall
         (for [el @elems] ^{:key el}
           [:> rtg/CSSTransition
            {:timeout {:enter 1000, :exit 1000}
             :classNames "elem"
             :appear true
             :appeartimeout 500}
            [:span.elem (str " - " el " ")]]))]]
      [:div.elem
        (doall (for [el @elems] ^{:key el}
          [:span (str " - " el " ")]))]
      [:button {:on-click #(r/rswap! elems conj (inc (last @elems)))}
      ; [:button {:on-click #(reset! elems [0])}
      ; [:button {:on-click #(rf/dispatch [:conj [:state :elems] (inc (last @elems))])}
       "more"]

      [:label [:input {:type "checkbox"
                       :on-click #(-> (js/document.querySelector "main")
                                      .-classList
                                      (.toggle "debug-on"))}]
       "Debug"]])

   [:div.parallax
    (map-indexed
     (fn [i group] ^{:key (str "parallax-group-" (inc i))}
       [:div.parallax__group {:id (str "group" (inc i))}
        (for [layer group] ^{:key (str "parallax-group-" (inc i) "-layer-" layer)}
          [:section.noborder.parallax__layer
           {:class (str "parallax__layer--" layer)}
           [:div  (str layer " layer")]])])
     [["base"]
      ["base" "back"]
      ["fore" "base"]
      ["base" "back" "deep"]
      ["fore" "base"]
      ["back" "base"]
      ["base"]])] ])


(defn code-mirror
  "Create a code-mirror editor. The parameters:
  value-atom (reagent atom)
    when this changes, the editor will update to reflect it.
  options
  :style (reagent style map)
    will be applied to the container element
  :js-cm-opts
    options passed into the CodeMirror constructor
  :on-cm-init (fn [cm] -> nil)
    called with the CodeMirror instance, for whatever extra fiddling you want to do."
  [& {:keys [style js-cm-opts on-cm-init]}]
  (let [cm (atom nil)
        model (atom "")]
    (r/create-class
     {:component-did-mount
      (fn [this]

        ; (let [inst (js/CodeMirror. ; or codemirror/CodeMirror.?
        ;             (r/dom-node this)
        ;             (clj->js
        ;              (merge
        ;               {:lineNumbers true
        ;                :viewportMargin js/Infinity
        ;                :matchBrackets true
        ;                :autofocus true
        ;                :value @model
        ;                :autoCloseBrackets true
        ;                :mode "markdown"}
        ;               js-cm-opts)))]
        ;   (reset! cm inst)
        ;   (.on inst "change"
        ;        (fn []
        ;          (let [value (.getValue inst)]
        ;            (when-not (= value @model)
        ;              (reset! model value)))))
        ;   (when on-cm-init
        ;     (on-cm-init inst))
        ;   #_(rdom/render inst (r/dom-node this)))
        )

      :component-did-update
      (fn [this old-argv]
        (when-not (= @model (.getValue @cm))
          (.setValue @cm @model)
          ;; reset the cursor to the end of the text, if the text was changed externally
          (let [last-line (.lastLine @cm)
                last-ch (count (.getLine @cm last-line))]
            (.setCursor @cm last-line last-ch))))

      :reagent-render
      (fn [_ _ _]
        @model ;how does deref to force render work when reg atom? trigs did-update?
        [:<> [:div {:style style} "fart"]
         ; [:p @model]
         ])}))) ; how is inst injected here lol?inst injected here lol?
        ; [@cm]
        ; [:div {:style style}])}))) ; how is inst injected here lol?inst injected here lol?


