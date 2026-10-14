(ns website.carousel-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.test :refer-macros [deftest is async]]
            [website.react :as r]

            [website.events]
            [website.subs]
            [website.modules.carousel.module]
            [website.modules.carousel.views :as views]
            [website.loader.style-catalog :as catalog]
            [website.test-support :as support]))

(deftest carousel-assets-follow-only-real-consumers
  (let [sheet "/css/website/modules/carousel.min.css"
        manifest (into {} (map (fn [[id spec]] [id (:paths spec)])) catalog/modules)]
    (doseq [module [:cv :home :strava]]
      (is (some #{:carousel} (catalog/dependencies module)))
      (is (some #{sheet} (catalog/initial-paths manifest module))))
    (doseq [module [:main :blog :search]]
      (is (not (some #{:carousel} (catalog/dependencies module)))))))

(deftest real-carousel-controls-cycle-and-instance-subscriptions-stay-scoped
  (async done
    (-> (go-promise
          (let [restore-db! (r/make-restore-fn)
                element (.createElement js/document "div")
                root (await! (support/create-root! element))]
            (try
              (r/dispatch-sync [:init/app-db])
              (await! (support/render! root
                       [views/<carousel-normal> :test {} [[:p "One"] [:p "Two"] [:p "Three"]]]))
              (await! (support/wait-for! #(.querySelector element ".carousel-item-main")))
              (let [current #(.-textContent (.querySelector element ".carousel-item-main"))]
                (is (= "One" (current)))
                (.click (.querySelector element ".carousel-next-btn"))
                (await! (support/settle!))
                (is (= "Two" (current)))
                (.click (.querySelector element ".carousel-prev-btn"))
                (await! (support/settle!))
                (is (= "One" (current)))
                (.click (.querySelector element ".carousel-prev-btn"))
                (await! (support/settle!))
                (is (= "Three" (current)))
                (r/dispatch-sync [:carousel/set-index :other 1])
                (await! (support/settle!))
                (is (= "Three" (current)) "Another carousel's index never changes this instance"))
              (finally (support/unmount! root) (restore-db!)))))
        (.catch #(is false (str %)))
        (.finally done))))
