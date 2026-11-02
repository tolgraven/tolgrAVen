(ns website.components.ui.diagnostics
  (:require [website.component.registry]
            [website.react :as r]
            [website.util :as util :refer [at]]
            [website.macros :as m :include-macros true]
            [website.components.ui.controls :as controls]
            [website.modules.highlight.code-block :as code]))


(defn format-log-message [message]
  (pr-str message))

(m/defc <message>
  "Preserve diagnostic source/layout while the shared formatter acquires tokens."
  [message]
  [:pre
   [code/<formatter> (format-log-message message)
    {:inline? true :default-language "clojure" :auto-languages ["clojure"]}
    (constantly nil)]])

(m/defc ^:private <log-line> [{:keys [time level title message]}]
  [:tr.log-messages
   [:td.log-time (util/unix->ts time)]
   [:td.log-level {:class (name level)} (name level)]
   [:td.log-title title]
   [:td.log-message [<message> message]]])

(m/defc <log>
  "Show an expandable log/feed, following new messages after the first render."
  [options content]
  :let [*table (atom nil)
        *mounted? (atom false)]
  (r/use-layout-effect
   (fn []
     (when @*mounted? (util/scroll-to-end! @*table))
     (reset! *mounted? true)
     nil))
  (let [messages (:messages @content)]
    [:section#log-container.log-container.solid-bg
     [controls/<minimize> [:state [:display :log]]]
     [:table
      {:ref #(reset! *table %)
       :style {:max-height (if (:minimized @options) "1.2em" "20em")}}
      [:tbody.log ; TODO sort by time
       (for [msg #_(into (apply sorted-map-by :time messages))
             (map messages (sort-by str (keys messages))
                  ; (if (:minimized @options) ; upside-down?
                  ;   [(count messages)]
                  ;   (sort (keys messages)))
                  )]
         ^{:key (str (:id msg))}
         [<log-line> msg])]]]))

(m/register-render-functions!)
