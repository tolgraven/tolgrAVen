(ns tolgraven.neovim-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [tolgraven.modules.neovim.model :as model]
            [tolgraven.modules.neovim.policy :as policy]
            [tolgraven.modules.neovim.files :as files]))

(deftest redraw-is-a-pure-projection
  (let [original (model/grid 5 3)
        painted (model/redraw original
                  [["grid_line" [1 0 0 [["<" 2] ["&"] ["x" 0 3]]]]
                   ["grid_cursor_goto" [1 0 2]]
                   ["hl_attr_define" [2 {:foreground 16711680} [] []]]
                   ["mode_change" ["insert" 1]] ["flush" []]])]
    (is (= [" " 0] (get-in original [:cells 0 0])))
    (is (= [["<" 2] ["&" 2] ["x" 0] ["x" 0] ["x" 0]] (first (:cells painted))))
    (is (= [0 2] (:cursor painted)))
    (is (= "insert" (:mode painted)))
    (is (= "#ff0000" (:color (model/highlight-style painted (get-in painted [:highlights 2])))))))

(deftest scroll-uses-the-original-region
  (let [painted (model/redraw (model/grid 4 3)
                  [["grid_line" [1 0 0 [["a" 1 4]]] [1 1 0 [["b" 2 4]]] [1 2 0 [["c" 3 4]]]]])
        up (model/redraw painted [["grid_scroll" [1 0 3 1 3 1 0]]])
        down (model/redraw painted [["grid_scroll" [1 0 3 1 3 -1 0]]])]
    (is (= [["a" 1] ["b" 2] ["b" 2] ["a" 1]] (first (:cells up))))
    (is (= [["c" 3] [" " 0] [" " 0] ["c" 3]] (last (:cells up))))
    (is (= [["a" 1] [" " 0] [" " 0] ["a" 1]] (first (:cells down))))
    (is (= [["c" 3] ["b" 2] ["b" 2] ["c" 3]] (last (:cells down))))))

(deftest key-translation-preserves-native-shortcuts-and-modifiers
  (doseq [[event expected] [[{:key "<"} "<lt>"]
                           [{:key "Tab", :shift? true} "<S-Tab>"]
                           [{:key "ArrowLeft", :ctrl? true} "<C-Left>"]
                           [{:key "x", :ctrl? true} "<C-x>"]
                           [{:key "v", :meta? true} nil]
                           [{:key "v", :ctrl? true, :shift? true} nil]
                           [{:key "あ", :composing? true} nil]
                           [{:key "Shift"} nil]]]
    (is (= expected (model/key-input event)))))

(deftest cached-documents-share-network-isolation-policy
  (is (= "require-corp" (get (policy/headers-for "/neovim") "Cross-Origin-Embedder-Policy")))
  (is (= "same-origin" (get (policy/headers-for "/vendor/neovim/nvim-worker.js") "Cross-Origin-Opener-Policy")))
  (is (nil? (policy/headers-for "/blog")))
  (is (nil? (policy/headers-for "/neovim-other"))))

(deftest text-runs-preserve-width-highlights-and-the-cursor
  (let [runs (model/row-runs [["你" 2] ["" 2] ["<" 0] ["&" 0]] 0)]
    (is (= 2 (count runs)))
    (is (= {:col 0, :width 2, :text "你", :highlight 2, :cursor? true} (first runs)))
    (is (= {:col 2, :width 2, :text "<&", :highlight 0, :cursor? false} (second runs))))
  (is (= 1 (count (model/row-runs [["a" 0] ["b" 0] [" " 0]] -1))))
  (is (not-any? :cursor? (model/row-runs [["" 0] ["a" 0]] -1))))

(deftest cursors-invert-the-cell-and-follow-native-mode-shapes
  (let [grid (model/redraw (model/grid 3 1)
               [["mode_info_set" [true [{:cursor_shape "block"} {:cursor_shape "vertical", :cell_percentage 25}]]]
                ["mode_change" ["normal" 0]]])
        highlight {:foreground 16711680, :background 255, :bold true}
        block (model/cursor-style grid highlight)
        insert (model/redraw grid [["mode_change" ["insert" 1]]])
        bar (model/cursor-style insert highlight)]
    (is (= "block" (model/cursor-shape grid)))
    (is (= "#0000ff" (:color block)))
    (is (= "#ff0000" (:background-color block)))
    (is (= "bold" (:font-weight block)))
    (is (= "vertical" (model/cursor-shape insert)))
    (is (= "#ff0000" (:color bar)))
    (is (= "#0000ff" (:background-color bar)))
    (is (= "25%" (get bar "--cursor-size")))
    (is (= "#ff0000" (get bar "--cursor-color")))))

(deftest imports-keep-relative-paths-with-bounded-file-plans
  (doseq [path ["hello.txt" "project/src/core.clj" ".hidden" "a..txt"]]
    (is (files/safe-path? path)))
  (doseq [path ["/root.txt" "../root.txt" "src/../root.txt" "src//file" "src\\file" ""]]
    (is (not (files/safe-path? path))))
  (is (nil? (files/plan-error [{:path "src/core.clj", :size 100}])))
  (is (some? (files/plan-error [{:path "../core.clj", :size 100}])))
  (is (some? (files/plan-error [{:path "a", :size 1} {:path "a", :size 2}])))
  (is (some? (files/plan-error [{:path "large", :size (inc files/max-file-bytes)}]))))

(deftest cursor-highlight-ids-override-matching-bracket-colors
  (let [grid (model/redraw (model/grid 2 1)
               [["mode_info_set" [true [{:cursor_shape "block", :attr_id 7}]]]
                ["hl_attr_define" [7 {:foreground 0, :background 16777215} [] []]]])
        style (model/cursor-style grid {:foreground 16711680, :background 255, :reverse true})]
    (is (= "#000000" (:color style)))
    (is (= "#ffffff" (:background-color style)))))

(deftest mouse-events-use-grid-cells-and-follow-native-enable-state
  (let [geometry {:left 10
                  :top 20
                  :cell-width 8
                  :cell-height 16
                  :cols 80
                  :rows 24}]
    (is (= [2 3] (model/mouse-position (assoc geometry :x 38 :y 60))))
    (is (= [0 0] (model/mouse-position (assoc geometry :x -100 :y -100))))
    (is (= [23 79] (model/mouse-position (assoc geometry :x 10000 :y 10000)))))
  (is (= "left" (model/mouse-button 0)))
  (is (= "right" (model/mouse-button 2)))
  (is (nil? (model/mouse-button -1)))
  (is (= "SCA" (model/mouse-modifiers {:shift? true, :ctrl? true, :alt? true})))
  (is (= "D" (model/mouse-modifiers {:meta? true})))
  (let [enabled (model/redraw (model/grid 5 3) [["mouse_on" []]])]
    (is (:mouse? enabled))
    (is (not (:mouse? (model/redraw enabled [["mouse_off" []]]))))))
