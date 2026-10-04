(ns tolgraven.components.init
  (:require
    [tolgraven.component.registry]
    [tolgraven.macros :refer-macros [defc]]
    [tolgraven.react :as rf]
    [tolgraven.components.error :as error]))

(defc <fallback> [retry!]
  (let [{:keys [status]} @(rf/subscribe [:state [:page-init]])]
    (when (= :failed status)
      [error/<failure> "page" "init"
       {:title "The page could not initialize"
        :message "Check your connection and retry. The failure has been recorded in the webpage log."}
       retry!])))
