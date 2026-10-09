(ns tolgraven.search-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [async deftest is]]
            [reagent.core :as r]
            [tolgraven.test-support :as support]
            [tolgraven.modules.search.module]
            [tolgraven.modules.search.views :as search]))

(deftest search-releases-focus-on-close-and-cancels-pending-focus
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                *open? (r/atom true)
                *model (r/atom "")
                scroll-y (.-scrollY js/window)]
            (.appendChild (.-body js/document) container)
            (let [root (await! (support/create-root! container))]
              (try
                (await! (support/render! root
                          [(fn [] [search/<box> ["blog-posts"]
                                   :model *model
                                   :open? @*open?])]))
                (let [input (.querySelector container "#search-input")]
                  (await! (support/wait-for! #(identical? input (.-activeElement js/document))))
                  (is (identical? input (.-activeElement js/document)))
                  (reset! *open? false)
                  (await! (support/settle!))
                  (is (not (identical? input (.-activeElement js/document)))
                      "Collapsed Search must not keep a hidden caret focused")
                  (reset! *open? true)
                  (await! (support/settle!))
                  (reset! *open? false)
                  (await! (support/settle!))
                  (await! (js/Promise. (fn [resolve _] (js/setTimeout resolve 150))))
                  (is (not (identical? input (.-activeElement js/document)))
                      "Closing before the delayed focus cancels that focus"))
                (finally
                  (support/unmount! root)
                  (.remove container)
                  (.scrollTo js/window #js {:top scroll-y :behavior "instant"}))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
