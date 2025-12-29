(ns tolgraven.macros
  #?(:clj (:refer-clojure :exclude [for]))
  (:require [clojure.string :as string])
  #?(:cljs (:require [reagent.core :as r]
                     [re-frame.core :as rf]
                     [shadow.lazy]
                     [tolgraven.components.error :as error]
                     [tolgraven.util :as util]))
  #?(:cljs (:require-macros [tolgraven.macros])))

(defmacro handler-fn "Use in event-handlers instead of (fn [e/_]), returns nil so react doesnt get a false and ignore us"
  ([& body]
    `(fn [~'event] ~@body nil)))  ;; force return nil

(defmacro ors "(or), but treats empty string as nil. not working"
  ([] nil)
  ([x]
   (when-not (clojure.string/blank? x)
     x))
  ([x & next]
   `(let [or# ~x]
          (if or# or# (ors ~@next)))))

(defmacro for
  "Like `clojure.core/for`, but non-lazy, and injects `:key` metadata into each
  item."
  [[id xs & ls] <c>]
  `(doall (clojure.core/for [[i# ~id] (map-indexed vector ~xs) ~@ls]
            (with-meta ~<c> {:key i#}))))

(defmacro make-modules
  "Use keywords to generate Shadow lazy loadables.
   ks must be a literal seq of keywords at the call site."
  [base ks]
  (let [base-str (str base)]
    `(hash-map
       ~@(mapcat
           (fn [k]
             (let [qsym# (symbol (str base-str "." (name k) ".module/spec"))]
               [k `(shadow.lazy/loadable ~qsym#)]))
           ks))))


;; also should include tooltip popup functionality
;; other possible route is middleware style where each piece of functionality
;; is merged onto the component? but could that actually be done without wrapping?
;;
;; random thought:
;; if doing placeholders and (content) loading support, how determine it?
;; might want to hook something on when all subcomponents are ready somehow.
;; if use a (reader?) macro at usage time, could gather such stuff.
;; but would be best _after_ reagent does its walk?
;; skip all that in beginning anyways.
;;
;; basic feature flag/disable component entirely support could go in here as well
;; prob do some lookups by sub through passing a namespaced id key in spec
;; including whether is enabled
(defmacro defcomp
  "Define a reagent component with a docstring and metadata, with a bunch of
   built-in functionality that doesn't wrap but is part of the component itself.
  Usage:
  (defcomp ^:private <my-component>
    \"This is my component\"
    [spec & form]
    (into [:div (:props spec)] form))"
  [name & decls]
  (let [docstring (when (string? (first decls)) (first decls))
        decls     (if docstring (next decls) decls)
        meta-map  (when (map? (first decls)) (first decls))
        decls     (if meta-map (next decls) decls)
        [args & body] decls
        [lets body] (if (and (seq body)
                             (= :let (first body))
                             (vector? (second body)))
                      [(second body) (nnext body)]
                      [nil body])
        ns-name   (or (some-> &env :ns :name) (ns-name *ns*)) ; cljs + clj
        spec-sym  (when (vector? args) (first args))]
    `(defn ~name
       ~@(when docstring [docstring])
       ~@(when meta-map [meta-map])
       ~args
       (let [*error# (clojure.core/atom nil)
             *mounted?# (reagent.core/atom nil)
             *showing?# (reagent.core/atom nil)
             spec#   ~spec-sym
             ~@(when lets [lets])]
         (reagent.core/create-class
          {:display-name ~(str name)
           :component-did-mount
           (fn [this#]
             (reset! *mounted?# true)
             (and (fn? (:init spec#))
                  ((:init spec#) this#)))
          :component-will-unmount
          (fn [this#]
            (reset! *mounted?# false)
            (and (fn? (:exit spec#))
                 ((:exit spec#) this#)))
           :component-did-catch
           (fn [this# error# info#]
             (let [stack# (some-> ^js info# .-componentStack)]
               (reset! *error# {:error error# :stack stack#}))
             (util/log :error (str "Error " ~(str name))
                       (ex-message error#))
             (.forceUpdate ^js this#))
           ; :component-did-update (fn [_this# _old-argv#] ; not working, clears error by itself
           ;                         (when @*error# (reset! *error# nil)))
           ; :get-derived-state-from-error (fn [error#]
           ;                                 (reset! *error# {:error error#})
           ;                                 {})
           :reagent-render
           (fn ~(symbol (str name "-inner"))
             ~args
             (letfn
               [(merge-props-into-root# [el#]
                  ;; Only merge when: vector hiccup and not a fragment
                  (if (and (vector? el#)
                           (not= :<> (first el#))
                           ~@(when spec-sym [`(map? ~spec-sym)])) ; if no spec arg, skip
                    (let [[tag# maybe-attrs# & children#] el#
                          has-attrs?# (map? maybe-attrs#)
                          classlist# (string/join
                                      " "
                                      (concat [(:class (:props ~spec-sym))]
                                              (:classes ~spec-sym)
                                              (case @*mounted?#
                                                true  ["TOL_mounted"]
                                                false ["TOL_mounted" "TOL_unmounted"]
                                                nil   [])))
                          base-attrs#   (if has-attrs?# maybe-attrs# {})
                          merged-attrs# (if ~spec-sym
                                          (merge base-attrs#
                                                 (:props ~spec-sym)
                                                 {:class classlist#})
                                          base-attrs#)
                          head#         [tag# merged-attrs#]]
                      (into head# (if has-attrs?#
                                    children#
                                    (cons maybe-attrs# children#))))
                    el#))]
               (if-not @*error#
                 (merge-props-into-root# (do ~@body))
                 [tolgraven.components.error/<error>
                  ~(str ns-name) ~(str name) *error# ~spec-sym])))})))))

(defmacro defcomp-
  "Define a reagent component with a docstring and metadata, standardized arg
   parsing, but no extra functionality like error boundaries and such.
  Usage:
  (defcomp- <small-component>
    \"This is a light component\"
    [spec-or-form]
    (into [:div (:props spec)] form))
   Meaning (TODO) a single arg gets destructured as spec containing form, or
   component not taking a form."
  [name & decls]
  (let [docstring     (when (string? (first decls)) (first decls))
        decls         (if docstring (next decls) decls)
        meta-map      (when (map? (first decls)) (first decls))
        decls         (if meta-map (next decls) decls)
        [args & body] decls
        [lets body]   (if (and (seq body)
                               (= :let (first body))
                               (vector? (second body)))
                        [(second body) (nnext body)]
                        [nil body])
        spec-sym      (when (vector? args) (first args))]
    `(defn ~name
       ~@(when docstring [docstring])
       ~@(when meta-map [meta-map])
       ~args
       (let [spec#   ~spec-sym
             ~@(when lets [lets])]
         (reagent.core/create-class
          {:display-name ~(str name)
           :component-did-mount
           (fn [this#]
             (and (fn? (:init spec#))
                  ((:init spec#) this#)))
          :component-will-unmount
          (fn [this#]
            (and (fn? (:exit spec#))
                 ((:exit spec#) this#)))
           :reagent-render
           (fn ~(symbol (str name "-inner"))
             ~args
             (letfn [(merge-props-into-root# [el#]
                        ;; Only merge when: vector hiccup and not a fragment
                        (if (and (vector? el#)
                                 (not= :<> (first el#))
                                 ~@(when spec-sym [`(map? ~spec-sym)])) ; if no spec arg, skip
                          (let [[tag# maybe-attrs# & children#] el#
                                has-attrs?# (map? maybe-attrs#)
                                base-attrs# (if has-attrs?# maybe-attrs# {})
                                merged-attrs# (if ~spec-sym
                                                (merge base-attrs# (:props ~spec-sym))
                                                base-attrs#)
                                head# (if has-attrs?#
                                        [tag# merged-attrs#]
                                        [tag# merged-attrs#])]
                            (into head# (if has-attrs?# children# (cons maybe-attrs# children#))))
                          el#))]
               (merge-props-into-root# (do ~@body))))})))))
; (s/defn <boundary> :- r/Component
;   "Util for inserting an error boundaries in the React tree. When a child throws
;   an error, just renders that error instead of crashing."
;   [& args]
;   (let [*error   (atom nil)
;         spec     (when (map? (first args)) (first args))
;         on-catch (fn [this error i]
;                    (when-not standalone?
;                      (let [stack (.-componentStack ^js i)]
;                        (reset! *error [error stack])
;                        (when (conf/get-error-reports-enabled)
;                          (report-error! error stack)))
;                      (.forceUpdate ^js this)))]
;     (letfn [(<boundary-inner> [this]
;               (r/as-element
;                (if-let [[error stack] @*error]
;                  [<error-container> (assoc spec :error error :stack stack)]
;                  (into [:<>] (r/children this)))))]
;       (r/create-class
;        {:component-did-catch          on-catch
;         :component-did-update         #(reset! *error nil)
;         :display-name                 "error-boundary"
;         :get-derived-state-from-error #(reset! *error [%])
;         :render                       <boundary-inner>}))))

