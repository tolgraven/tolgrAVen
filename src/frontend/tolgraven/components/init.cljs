(ns tolgraven.components.init
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as r]
    [tolgraven.components.error :as error]))

(defc <fallback> [retry!]
  (let [{:keys [status]} @(r/subscribe [:state [:page-init]])]
    (when (= :failed status)
      [error/<failure> "page" "init"
       {:title "The page could not initialize"
        :message "Check your connection and retry. The failure has been recorded in the webpage log."}
       retry!])))
