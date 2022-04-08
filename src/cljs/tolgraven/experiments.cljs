(ns tolgraven.experiments
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [re-frame.core :as rf]
            [tolgraven.ui :as ui]
            [cljsjs.codemirror :as codemirror]
            [cljsjs.codemirror.keymap.vim]
            [cljsjs.codemirror.mode.markdown]
            [cljsjs.react-leaflet]
            [cljsjs.leaflet]
            [react-transition-group :as rtg]))

; (def css-trans-group (r/adapt-react-class rtg/CSSTransitionGroup))

;       (.add "elem-enter"))))
(defn transition-group "Might be good exercise in React to try implement in cljs?
                        Though understanding what causing issues / moving away from cljsjs would
                        likely be even better exercise in productivity. Q is what sought"
  [attrs & components])

(defn parallax []
  [:<>
   (let [elems (or (rf/subscribe [:state [:elems]]) [1 2 3])]
     [:div.parallax-ui ;.fullwide
      [:div "Pure CSS parallax scroll demo #3 by Keith Clark -> tolgraven"]
      [:> rtg/TransitionGroup
       [:div.elem-group.flex
        (doall
         (for [el @elems] ^{:key el}
           [:> rtg/CSSTransition
            {:timeout {:enter 1000, :exit 1000}
             :classNames "elem"
             :appear true
             :appeartimeout 500}
            [:div.elem el]]))]]
      [:br]
      
      [:div.elem-group
        (doall (for [el @elems] ^{:key el}
          [ui/appear-anon "opacity" [:div el]]))]

      [:button {:on-click #(rf/dispatch [:conj [:state :elems]
                                               (inc (first @elems))])}
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
  (let [cm (r/atom nil)
        model (r/atom "")]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (let [inst (js/CodeMirror. ; or codemirror/CodeMirror.?
                    (r/dom-node this)
                    (clj->js
                     (merge
                      {:lineNumbers true
                       :viewportMargin js/Infinity
                       :matchBrackets true
                       :autofocus true
                       :value @model
                       :autoCloseBrackets true
                       :mode "markdown"}
                      js-cm-opts)))]
          (reset! cm inst)
          (.on inst "change"
               (fn []
                 (let [value (.getValue inst)]
                   (when-not (= value @model)
                     (reset! model value)))))
          (when on-cm-init
            (on-cm-init inst))
          ; (rdom/render inst (r/dom-node this))
          )
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
        [:textarea]
        ; @model ;how does deref to force render work when reg atom? trigs did-update?
        ; [:<> [:div {:style style} "fart"]
        ;  ; [:p @model]
        ;  ])}))) ; how is inst injected here lol?inst injected here lol?
        ; (if @cm
        ;   [:div (js->clj @cm)]
        ;   [:div "Some bullshit"])
        )}))) ; how is inst injected here lol?inst injected here lol?

(defn leaflet []
 [:section.leaflet-test
  [:div.covering-2
   {:style {:background "black"
            :z-index 50
            :width "100%"
            :height "30em"}}
   [:> js/ReactLeaflet.MapContainer
    {:center [59.272861 18.067398]
     :zoom 13}

    [:> js/ReactLeaflet.TileLayer
     {:attribution "&copy; contributors"
      :url "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"}] ]]])




