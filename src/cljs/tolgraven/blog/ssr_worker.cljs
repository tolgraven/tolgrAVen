(ns tolgraven.blog.ssr-worker
  (:require ["node:readline" :as readline]
            [reagent.dom.server :as server]
            [tolgraven.blog.ssr-view :as view]))

(defn main []
  (-> (.createInterface readline #js {:input (.-stdin js/process) :crlfDelay js/Infinity})
      (.on "close" (fn [] (.end (.-stdout js/process) #(js/process.exit 0))))
      (.on
       "line"
       (fn [line]
         (let [response (try
                          {:html (server/render-to-string
                                  [view/<page> (js->clj (js/JSON.parse line) :keywordize-keys true)])}
                          (catch :default error
                            (.write (.-stderr js/process) (str (.-stack error) "\n"))
                            {:error "Blog rendering failed"}))]
           (.write (.-stdout js/process) (str (js/JSON.stringify (clj->js response)) "\n")))))))
