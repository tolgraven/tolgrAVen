(ns website.components.ui.inputs
  (:require [website.component.registry]
            [website.loader.view :as loader-view]
            [website.react :as r]
            [website.util :as util :refer [at]]
            [website.macros :as m :include-macros true]
            [clojure.string :as string]))



; was trying to adapt this when still learning from zero using re-com
; none of it makes sense _and_ it's dropping keystrokes anyways haha
; plus need to diverge code for textarea really...
; basically gör om gör rätt
; textarea diffs:
; don't swallow enter (fucks repeat)
; maybe use ext md editor?
; alt-enter shortcut for post + others, re-press is kb shortcut lib

(m/defc <input-text-2> "Returns markup for a basic text input label"
 [& {:as args :keys [value path on-enter]}]
 (let [sub-or-val     #(or @(r/subscribe path) (at value))
       external-model (r/atom (sub-or-val)) ;ok so why does (sub in ratom...) work, straight subscribe not...
       internal-model (r/atom (if (some? @external-model) @external-model ""))] ;; Create a new atom from the model to be used internally (avoid nil)
  (fn [& {:as args
          :keys [input-type value path on-enter on-change placeholder
                 width height change-on-blur? disabled?
                 class style attr]
          :or {input-type :input.form-control
               width 100}}] ;to pass through from outer

   (let [latest-ext-model (sub-or-val) ;how repl this if not passing model but sub?
         sync-ext         #(r/dispatch (into path @internal-model))
         disabled?        (at disabled?)
         style            (merge {:display "inline-flex" :flex "1 1 auto"
                                  :width width ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
                                  :height height}
                                 style)
         change-on-blur?  (at change-on-blur?)
         on-blur          (fn [e]
                            (when (and on-change change-on-blur?
                                       (not= @internal-model @external-model))
                              (sync-ext) ; (reset! external-model @internal-model) ;well havoc if is a sub?
                              (on-change @internal-model)))
         on-change-fn (fn [e]
                        (let [new-val (-> e .-target .-value)]
                          (when (and on-change (not disabled?))
                            (reset! internal-model new-val)
                            (when-not change-on-blur?
                              (sync-ext) ; (reset! external-model @internal-model) ;uhh cant do that obviously
                              (on-change @internal-model)))))
         on-key-up (fn [e]
                     (if disabled?
                       (.preventDefault e)
                       (case (.-key e)
                         "Enter" (if change-on-blur?
                                   (do (sync-ext) ;(reset! external-model @internal-model)
                                       (when on-change (on-change @internal-model)))
                                   (when (and on-enter (not= "" @internal-model))
                                     (on-enter @internal-model)))
                         "Escape" (if change-on-blur?
                                    (reset! internal-model @external-model)) ;already loses focus automatically when press esc
                         true)))]
    (when (not= @external-model latest-ext-model) ;; Has model changed externally?
     (reset! external-model latest-ext-model)
     (reset! internal-model latest-ext-model))
    [input-type
     (merge {:class       class, ;:type "search" ;for clear button ;"text"
             :style       style       ; user best wrap in div or pass class for more fine grained control either way
             :placeholder placeholder
             :value       @internal-model
             :disabled    disabled?
             :on-change   on-change-fn
             :on-blur     on-blur
             :on-key-up   on-key-up}
            attr)])))) ;after not before, want to be able to override stuff duh
;
(m/defc <input-text> "Returns markup for a basic text input label"
 [& {:as args :keys [value path on-enter]}]
 (let [external-model (r/atom (or (r/subscribe path) (at value))) ;ok so why does (sub in ratom...) work, straight subscribe not...
       internal-model (r/atom (if (nil? @external-model) "" @external-model)) ;; Create a new atom from the model to be used internally (avoid nil)
       div-ref        (r/atom nil)]
   (fn [& {:keys [value path on-enter on-change placeholder width height min-rows
                  change-on-blur? disabled? class style attr input-type type]
           :or {input-type :input.form-control
                width "15em"
                on-change #(r/dispatch-sync (into path [%1 %2]))}}] ;to pass through from outer
   (let [latest-ext-model (or @(r/subscribe path) (at value)) ;how repl this if not passing model but sub?
         disabled?        (at disabled?)
         change-on-blur?  (at change-on-blur?)
         id               (str "input-text-" (util/keyvec->str path))
         min-height (when (= input-type :textarea)
                      {:min-height (when @div-ref
                                     (-> @internal-model
                                         (str "-")
                                         string/split-lines
                                         count (* 1.15) (+ 2)
                                         (->>
                                          (util/em->px id))
                                         (str "px")))})]
    (when (not= @external-model latest-ext-model) ;; Has model changed externally?
     (reset! external-model latest-ext-model)
     (reset! internal-model latest-ext-model))
    [input-type
     (merge {:id id
             :class class, :type type ;for clear button ;"text"
             :style (merge {:display "inline-flex" :flex "1 1 auto"
                            :width width ; how do like "min-width 'chars in str model + 10' up til 200 pixels yada?"
                            :min-height height}       ; user best wrap in div or pass class for more fine grained control either way
                           style
                           min-height)
             :placeholder placeholder
             :autoComplete (string/lower-case placeholder)
             :value       @internal-model
             :disabled    disabled?
             :rows        min-rows
             :ref         (fn [el] (reset! div-ref el))
             :on-change (fn [e]
                         (let [new-val (-> e .-target .-value)]
                          (when (and on-change (not disabled?))
                           (reset! internal-model new-val)
                           (reset! external-model @internal-model) ;uhh cant do that obviously
                           (on-change @internal-model))))
             :on-blur   (fn [e]
                         (when (and on-change #_change-on-blur?
                                    #_(not= @internal-model @external-model))
                          (reset! external-model @internal-model) ;nor here...
                          (on-change @internal-model :blur)))
             :on-key-up (fn [e]
                         (if disabled? (.preventDefault e)
                          (case (.-key e)
                           "Enter" (if change-on-blur?
                                    (do (reset! external-model @internal-model)
                                        (when on-change (on-change @internal-model)))
                                    (when (and on-enter (not= "" @internal-model))
                                     (on-enter @internal-model)))
                           "Escape" (if change-on-blur?
                                     (reset! internal-model @external-model)) ;already loses focus automatically when press esc
                           true)))}
            attr)])))) ;after not before, want to be able to override stuff duh


(defn <input-text-styled>
  "Acquire the custom field only when a consumer renders it."
  [& args]
  (loader-view/form :styled-input :input (vec args) nil))

(m/register-render-functions!)
