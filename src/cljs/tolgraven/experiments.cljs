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

(defn model-viewer
  []
  [:section
   [:model-viewer {:alt "A wooden pallet" :src "media/pallet.glb"
                   ; :ar true :ar-modes "webxr scene-viewer quick-look"
                   #_:environment-image #_"media/moon_1k.hdr" ;:poster "media/model.jpg"
                   #_:seamless-poster #_true :shadow-intensity "2"
                   ; :camera-orbit "45deg 255deg 0.5m"
                   :camera-orbit "calc(-1.5rad + env(window-scroll-y) * 4rad) calc(0deg + env(window-scroll-y) * 180deg) calc(3m - env(window-scroll-y) * 1.5m)" 
                   :auto-rotate true
                   :camera-controls true :enable-pan true}]])


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
                    ; (r/dom-node this)
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




; (defn leaflet []
;  (let [leaflet (atom nil)]
;    (fn []
;      [:section.leaflet-test
;       [:div.covering-2
;        {:style {:background "black"
;                 :z-index 50
;                 :width "100%"
;                 :height "30em"}}
;        [:div#leaflet
;         {:ref #(if (and % (not @leaflet))
;                  (reset! leaflet (.map js/L "leaflet"))
;                  (do
;                   (when @leaflet
;                     (.remove (.-map @leaflet)))
;                   (reset! leaflet nil)))}]]])))

; (defn leaflet []
;  (let [leaflet (atom nil)
;        tiles (js/L.tileLayer
;                "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
;                (clj->js {:attribution "yada"}))
;               ]
;    (fn []
;      [:section.leaflet-test
;       [:div.covering-2
;        {:style {:background "black"
;                 :z-index 50
;                 :width "100%"
;                 :height "30em"}}
;        [:div#leaflet
;         {:ref #(when (and % (not @leaflet))
;                  ; (.setView (.map js/L "map") #js [lat lng] 10))}]]])
;                  (reset! leaflet (.map js/L "leaflet"))
;                  #_(.addTo tiles @leaflet))}]]])))
(declare update-leaflet-geometries)

(defonce leaflet-map (r/atom nil))

(defn- leaflet-did-mount [this]
  "Initialize LeafletJS map for a newly mounted map component."
  (let [mapspec (:mapspec (r/state this))
        leaflet (when-not @leaflet-map
                  (reset! leaflet-map (js/L.map (:id mapspec))))
        view (:view mapspec)
        zoom (:zoom mapspec)]
    (.log js/console "L.map = " @leaflet-map)
    (.setView @leaflet-map (clj->js @view) @zoom)
    (doseq [{:keys [type url] :as layer-spec} (:layers mapspec)]
      (let [layer
            (case type
              :tile (js/L.tileLayer
                     url
                     (clj->js {:attribution (:attribution layer-spec)
                               :zoom-offset 1})
                     )
              :wms (js/L.tileLayer.wms
                    url
                    (clj->js {:format "image/png"
                              :fillOpacity 1.0
                              })))]
        ;;(.log js/console "L.tileLayer = " layer)
        (.addTo layer @leaflet-map)))
    (r/set-state this {:leaflet @leaflet-map
                       :geometries-map {}})

    (.invalidateSize @leaflet-map)

    ;; If mapspec defines callbacks, bind them to leaflet
    (when-let [on-click (:on-click mapspec)]
      (.on @leaflet-map "click" (fn [e]
                             (on-click [(-> e .-latlng .-lat) (-> e .-latlng .-lng)]))))

    ;; Add callback for leaflet pos/zoom changes
    ;; watcher for pos/zoom atoms
    (.on @leaflet-map "move" (fn [e]
                          (let [c (.getCenter @leaflet-map)]
                            (reset! zoom (.getZoom @leaflet-map))
                            (reset! view [(.-lat c) (.-lng c)]))))
    (add-watch view ::view-update
               (fn [_ _ old-view new-view]
                 ;;(.log js/console "change view: " (clj->js old-view) " => " (clj->js new-view) @zoom)
                 (when (not= old-view new-view)
                   (.setView @leaflet-map (clj->js new-view) @zoom))))
    (add-watch zoom ::zoom-update
               (fn [_ _ old-zoom new-zoom]
                 (when (not= old-zoom new-zoom)
                   (.setZoom @leaflet-map new-zoom))))
    ;; If the mapspec has an atom containing geometries, add watcher
    ;; so that we update all LeafletJS objects
    (when-let [g (:geometries mapspec)]
      (add-watch g ::geometries-update
                 (fn [_ _ _ new-geometries]
                   (update-leaflet-geometries this new-geometries))))))

(defn- leaflet-will-update [this old-state new-state]
  (update-leaflet-geometries this (-> this r/state :mapspec :geometries deref)))

(defn- leaflet-render [this]
  (let [mapspec (-> this r/state :mapspec)]
  [:div {:id (:id mapspec)
         :style {:width (:width mapspec)
                 :height (:height mapspec)}}]))

;;;;;;;;;;
;; Code to sync ClojureScript geometries vector data to LeafletJS
;; shape objects.

(defmulti create-shape :type)

(defmethod create-shape :polygon [{:keys [coordinates]}]
  (js/L.polygon (clj->js coordinates)
                        #js {:color "red"
                             :fillOpacity 0.5}))

(defmethod create-shape :line [{:keys [coordinates]}]
  (js/L.polyline (clj->js coordinates)
                 #js {:color "blue"}))

(defmethod create-shape :point [{:keys [coordinates]}]
  (js/L.circle (clj->js (first coordinates))
               10
               #js {:color "green"}))

(defn- update-leaflet-geometries [component geometries]
  "Update the LeafletJS layers based on the data, mutates the LeafletJS map object."
  (let [{:keys [leaflet geometries-map]} (r/state component)
        geometries-set (into #{} geometries)]
    ;; Remove all LeafletJS shape objects that are no longer in the new geometries
    (doseq [removed (keep (fn [[geom shape]]
                          (when-not (geometries-set geom)
                            shape))
                        geometries-map)]
      ;;(.log js/console "Removed: " removed)
      (.removeLayer leaflet removed))

    ;; Create new shapes for new geometries and update the geometries map
    (loop [new-geometries-map {}
           [geom & geometries] geometries]
      (if-not geom
        ;; Update component state with the new geometries map
        (r/set-state component {:geometries-map new-geometries-map})
        (if-let [existing-shape (geometries-map geom)]
          ;; Have existing shape, don't need to do anything
          (recur (assoc new-geometries-map geom existing-shape) geometries)

          ;; No existing shape, create a new shape and add it to the map
          (let [shape (create-shape geom)]
            ;;(.log js/console "Added: " (pr-str geom))
            (.addTo shape leaflet)
            (recur (assoc new-geometries-map geom shape) geometries)))))))


(defn leaflet-container [mapspec]
  "A LeafletJS map component."
  (r/create-class
    {:get-initial-state (fn [_] {:mapspec mapspec})
     :component-did-mount leaflet-did-mount
     :component-will-update leaflet-will-update
     :render leaflet-render}))


(def geometries (r/atom [{:type :polygon
                        :coordinates [[65.1 25.2]
                                      [65.15 25.2]
                                      [65.125 25.3]]}

                       {:type :line
                        :coordinates [[65.3 25.0]
                                      [65.4 25.5]]}]))

(def view-position (r/atom [65.1 25.2]))
(def zoom-level (r/atom 8))

(defn leaflet-react-component []
  (let [drawing (r/atom false)]
    (fn []
    [:span
     [leaflet-container
      {:id "kartta"
       :width "100%" :height "300px" ;; set width/height as CSS units
       :view view-position ;; map center position
       :zoom zoom-level ;; map zoom level

       ;; The actual map data (tile layers from OpenStreetMap), also supported is :wms type
       :layers [{:type :tile
                 :url "http://{s}.tile.osm.org/{z}/{x}/{y}.png"
                 :attribution "&copy; <a href=\"http://osm.org/copyright\">OpenStreetMap</a> contributors"}]

       :geometries geometries ;; Geometry shapes to draw to the map

       :on-click #(when @drawing ;; Add handler for map clicks
                    ;; if drawing, add point to polyline
                    (swap! geometries
                           (fn [geometries]
                             (let [pos (dec (count geometries))]
                               (assoc geometries pos
                                      {:type :line
                                       :coordinates (conj (:coordinates (nth geometries pos))
                                                          %)})))))} ]
     [:div.actions
      "Control the map position/zoom by swap!ing the atoms"
      [:br]
      [:button {:on-click #(swap! view-position update-in [1] - 0.2)} "left"]
      [:button {:on-click #(swap! view-position update-in [1] + 0.2)} "right"]
      [:button {:on-click #(swap! view-position update-in [0] + 0.2)} "up"]
      [:button {:on-click #(swap! view-position update-in [0] - 0.2)} "down"]
      [:button {:on-click #(swap! zoom-level inc)} "zoom in"]
      [:button {:on-click #(swap! zoom-level dec)} "zoom out"]]

     (if @drawing
       [:span
        [:button {:on-click #(do
                              (swap! geometries
                                     (fn [geometries]
                                       (let [pos (dec (count geometries))]
                                         (assoc geometries pos
                                           {:type :polygon
                                            :coordinates (:coordinates (nth geometries pos))}))))
                              (reset! drawing false))}
         "done drawing"]
        "start clicking points on the map, click \"done drawing\" when finished"]

       [:button {:on-click #(do
                              (.log js/console "drawing a poly")
                              (reset! drawing true)
                              (swap! geometries conj {:type :line
                                                      :coordinates []}))} "draw a polygon"])

     [:div.info
      [:b "current view pos: "] (pr-str @view-position) [:br]
      [:b "current zoom level: "] (pr-str @zoom-level)] ])))
