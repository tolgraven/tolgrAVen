(ns website.modules.link-preview.interface
  "Candidate-aware content stays renderable without acquiring preview widgets."
  (:require [website.react :as r]
            [website.macros :refer-macros [defc]]
            [website.loader :as loader]
            [website.render-context :as context]
            [website.modules.link-preview.util :as util]
            [website.modules.markdown.interface :as markdown]))

(def container-events
  {:on-pointer-over "pointerover"
   :on-pointer-out "pointerout"
   :on-focus "focusin"
   :on-blur "focusout"
   :on-pointer-down "pointerdown"
   :on-click "click"})

(defn container-attrs [id attrs]
  ;; React owns the event attributes before and after optional code arrival.
  ;; Native gesture handlers resolve against that module's committed registry.
  (reduce-kv
    (fn [result attribute event-name]
      (let [caller (get attrs attribute)]
        (assoc result attribute
          (fn [^js event]
            (when-let [handle! (get-in (loader/code-spec :link-preview)
                                      [:link-container :handle!])]
              (handle! id event-name event))
            (when (and caller (not (.isPropagationStopped event)))
              (caller event))))))
    (assoc attrs :data-link-container true)
    container-events))

(defc ^:private <observation>
  [id element candidates trust]
  (let [ready? (when-not context/*server?*
                 @(r/subscribe [:loader/module :link-preview]))
        observe! (when ready?
                   (get-in (loader/code-spec :link-preview)
                           [:link-container :observe!]))]
    ;; Only native observation is effect-owned. Acquisition and preview state use
    ;; the existing loader subscription and domain events; content never suspends.
    (r/use-effect
      (fn []
        (when (and observe! @element)
          (observe! id @element {:trust trust} candidates)))
      [observe! id candidates trust]))
  nil)

(defc <link-container>
  "Render content immediately; candidate-bearing containers acquire previews."
  [{:keys [id text trust]} content]
  :let [*element (atom nil)]
  (let [base-url (if (exists? js/window)
                   (.-href js/window.location)
                   (str "https://tolgraven.se" (:path @context/*snapshot)))
        candidates (util/external-urls text base-url)]
    [:div.link-preview-container
     (container-attrs id
       {:data-link-trust (when trust (name trust))
        :ref (fn [element] (reset! *element element) nil)})
     content
     (when (and (seq candidates)
                (or context/*server?* (contains? loader/modules :link-preview)))
       [<observation> id *element candidates trust])]))

(defc <md>
  "Render Markdown with optional previews without replacing its content tree."
  [md & [options]]
  :let [id (or (:id options) (str "markdown-" (random-uuid)))]
  [<link-container>
   {:id id
    :text md
    :trust (:trust options)}
   [markdown/<md->div> md options]])
