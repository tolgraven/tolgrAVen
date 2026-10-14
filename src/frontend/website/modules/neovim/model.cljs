(ns website.modules.neovim.model
  "Pure ext_linegrid presentation and native keyboard translation.
   The WASM process owns the buffer; this state is only its transient UI projection."
  (:require [clojure.string :as string]))

(def cell-schema [:tuple :string :int])
(def grid-schema
  [:map
   [:cells [:vector [:vector cell-schema]]]
   [:cursor [:tuple :int :int]]
   [:highlights [:map-of :int :map]]
   [:foreground :int]
   [:background :int]
   [:mode :string]
   [:modes [:vector :map]]
   [:mode-index :int]
   [:cursor-style? :boolean]
   [:mouse? :boolean]])

(defn blank-row [cols] (vec (repeat cols [" " 0])))
(defn grid [cols rows]
  {:cells (vec (repeat rows (blank-row cols)))
   :cursor [0 0]
   :highlights {}
   :foreground 14673393
   :background 330002
   :mode "normal"
   :modes []
   :mode-index 0
   :cursor-style? false
   :mouse? false})

(defn- line [state [_ row col cells]]
  (let [expanded (second
                   (reduce (fn [[previous result] [text highlight repeat-count]]
                             (let [highlight' (or highlight previous)]
                               [highlight' (into result (repeat (or repeat-count 1)
                                                              [text highlight']))]))
                           [0 []] cells))
        width (count (get-in state [:cells row]))]
    (reduce (fn [state' [column cell]]
              (if (< column width) (assoc-in state' [:cells row column] cell) state'))
            state (map vector (range col (+ col (count expanded))) expanded))))

(defn- scroll [state [_ top bottom left right rows _cols]]
  (let [cells (:cells state)]
    (reduce (fn [state' row]
              (let [source (+ row rows)]
                (reduce (fn [state'' col]
                          (assoc-in state'' [:cells row col]
                                    (if (<= top source (dec bottom))
                                      (get-in cells [source col]) [" " 0])))
                        state' (range left right))))
            state (range top bottom))))

(defn apply-entry [state name entry]
  (case name
    "grid_resize" (let [[id cols rows] entry]
                    (if (= 1 id) (assoc state :cells (:cells (grid cols rows))) state))
    "grid_clear" (if (= 1 (first entry))
                   (update state :cells #(mapv (fn [row] (blank-row (count row))) %)) state)
    "grid_line" (if (= 1 (first entry)) (line state entry) state)
    "grid_scroll" (if (= 1 (first entry)) (scroll state entry) state)
    "grid_cursor_goto" (if (= 1 (first entry)) (assoc state :cursor (vec (rest entry))) state)
    "hl_attr_define" (assoc-in state [:highlights (first entry)] (second entry))
    "default_colors_set" (let [[fg bg] entry]
                           (cond-> state (<= 0 fg) (assoc :foreground fg)
                                         (<= 0 bg) (assoc :background bg)))
    "mode_info_set" (assoc state :cursor-style? (first entry) :modes (vec (second entry)))
    "mode_change" (assoc state :mode (first entry) :mode-index (second entry))
    "mouse_on" (assoc state :mouse? true)
    "mouse_off" (assoc state :mouse? false)
    state))

(defn redraw [state events]
  (reduce (fn [state' [name & entries]]
            (reduce #(apply-entry %1 name %2) state' entries)) state events))

(defn row-runs [cells cursor-col]
  ;; Adjacent equal cells become one text run. Keep the cursor separate and give
  ;; a wide glyph both of its grid columns, including its empty continuation.
  (let [cursor-end (+ cursor-col (if (and (<= 0 cursor-col)
                                           (= "" (first (get cells (inc cursor-col))))) 1 0))]
    (->> (map-indexed vector cells)
         (partition-by (fn [[col [_ highlight]]]
                         [highlight (<= cursor-col col cursor-end)]))
         (mapv (fn [entries]
                 (let [[col [_ highlight]] (first entries)]
                   {:col col
                    :width (count entries)
                    :highlight highlight
                    :cursor? (<= cursor-col col cursor-end)
                    :text (apply str (map (comp first second) entries))}))))))

(defn color [value] (str "#" (.padStart (.toString value 16) 6 "0")))
(defn highlight-style [{:keys [foreground background]} highlight]
  (let [foreground' (or (:foreground highlight) foreground)
        background' (or (:background highlight) background)
        [fg bg] (if (:reverse highlight) [background' foreground'] [foreground' background'])]
    (cond-> {:color (color fg), :background-color (color bg)}
      (:bold highlight) (assoc :font-weight "bold")
      (:italic highlight) (assoc :font-style "italic")
      (or (:underline highlight) (:undercurl highlight)) (assoc :text-decoration "underline"))))

(defn cursor-shape [{:keys [modes mode-index cursor-style? mode]}]
  (or (when cursor-style? (:cursor_shape (get modes mode-index)))
      (if (string/starts-with? mode "insert") "vertical" "block")))

(defn cursor-style [grid highlight]
  (let [style (highlight-style grid highlight)
        mode (get (:modes grid) (:mode-index grid))
        cursor-highlight (when (pos? (or (:attr_id mode) 0))
                           (get (:highlights grid) (:attr_id mode)))
        cursor-colors (when (seq cursor-highlight) (highlight-style grid cursor-highlight))]
    (if (= "block" (cursor-shape grid))
      ;; UI protocol: use attr_id when supplied; attr_id 0 reverses the cell.
      ;; Keep the glyph in its original cell and preserve its text formatting.
      (merge style (if cursor-colors (select-keys cursor-colors [:color :background-color])
                      {:color (:background-color style), :background-color (:color style)}))
      (assoc style "--cursor-color" (or (:background-color cursor-colors) (:color style))
                   "--cursor-size" (str (or (:cell_percentage mode) 20) "%")))))

(def named-keys
  {"Backspace" "BS"
   "Enter" "CR"
   "Escape" "Esc"
   "Tab" "Tab"
   "ArrowUp" "Up"
   "ArrowDown" "Down"
   "ArrowLeft" "Left"
   "ArrowRight" "Right"
   "Delete" "Del"
   "Home" "Home"
   "End" "End"
   "PageUp" "PageUp"
   "PageDown" "PageDown"})

(defn key-input [{:keys [key ctrl? alt? shift? meta? composing?]}]
  (when-not (or composing? meta? (and ctrl? shift? (= "v" (string/lower-case key))))
    (let [named (get named-keys key)
          prefix (str (when ctrl? "C-") (when alt? "A-")
                      (when (and named shift?) "S-"))]
      (cond
        named (str "<" prefix named ">")
        (= 1 (count key)) (if (seq prefix) (str "<" prefix key ">")
                             (if (= "<" key) "<lt>" key))
        :else nil))))

(def mouse-params-schema
  [:tuple [:enum "left" "middle" "right" "x1" "x2" "wheel"]
   [:enum "press" "drag" "release" "up" "down" "left" "right"]
   :string [:= 0] [:int {:min 0}] [:int {:min 0}]])

(defn mouse-button [button]
  (get {0 "left", 1 "middle", 2 "right", 3 "x1", 4 "x2"} button))

(defn mouse-modifiers [{:keys [shift? ctrl? alt? meta?]}]
  (str (when shift? "S") (when ctrl? "C") (when alt? "A") (when meta? "D")))

(defn mouse-position [{:keys [x y left top cell-width cell-height cols rows]}]
  ;; Clamp captured drags outside the grid to its nearest cell.
  [(max 0 (min (dec rows) (js/Math.floor (/ (- y top) cell-height))))
   (max 0 (min (dec cols) (js/Math.floor (/ (- x left) cell-width))))])
