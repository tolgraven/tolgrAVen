(ns tolgraven.react 
  (:refer-clojure :exclude [atom]) 
  (:require
   [react-dom :as react-dom]
   [react :as react]
   [reagent.core :as r]
   [reagent.dom]
   [reagent.ratom]))

(def adapt-react-class         reagent.core/adapt-react-class)
(def argv                      reagent.core/argv)
(def as-element                reagent.core/as-element)
(def atom                      reagent.core/atom)
(def children                  reagent.core/children)
(def create-class              reagent.core/create-class)
(def cursor                    reagent.core/cursor)
(def dom-node                  reagent.dom/dom-node)
(def lazy                      react/lazy)
(def reactify-component        reagent.core/reactify-component)
(def render                    reagent.dom/render)
(def make-reaction             reagent.ratom/make-reaction)
(def track                     reagent.core/track)
(def track!                    reagent.core/track!)
(def create-portal             react-dom/createPortal)
(def suspense                  (adapt-react-class react/Suspense))
(def unmount-component-at-node reagent.dom/unmount-component-at-node)

