(ns tolgraven.github-test
  (:require-macros [tolgraven.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as string]
            [malli.core :as m]
            [tolgraven.modules.github.message :as message]
            [tolgraven.modules.github.schema :as schema]
            [tolgraven.modules.github.views :as views]
            [tolgraven.test-support :as support]))

(deftest commit-message-styles
  (doseq [[input expected]
          [["scss: fix theme helper" ["fix theme helper" "scss" nil]]
           ["scss: theme: fix helper" ["fix helper" "theme" "scss"]]
           ["fix(github): wrap commit tags" ["wrap commit tags" "github" "fix"]]
           ["feat(api)!: change contract" ["change contract" "api" "feat!"]]
           ["fix!: change contract" ["change contract" "fix!" nil]]
           ["Fix wrapping for commit messages" ["Fix wrapping for commit messages" nil nil]]
           ["Merge pull request #42 from codex/github" ["Merge pull request #42 from codex/github" nil nil]]
           ["Revert \"fix: wrapping\"" ["Revert \"fix: wrapping\"" nil nil]]
           ["Fix layout: avoid sideways scrolling" ["Fix layout: avoid sideways scrolling" nil nil]]
           ["fix: keep https://example.com:4000/path" ["keep https://example.com:4000/path" "fix" nil]]
           ["fix: explain the reason: preserve colons" ["explain the reason: preserve colons" "fix" nil]]
           ["fix: subject\n\nNotes: body stays intact\nhttps://example.com"
            ["subject\n\nNotes: body stays intact\nhttps://example.com" "fix" nil]]
           [(str (string/join (repeat 80 "x")) ": subject")
            [(str (string/join (repeat 80 "x")) ": subject") nil nil]]
           [nil ["" nil nil]]
           ["" ["" nil nil]]]]
    (testing (str input)
      (let [parsed (message/parse-message input)]
        (is (= expected parsed))
        (is (m/validate schema/parsed-message parsed))))))

(deftest commit-messages-fit-narrow-and-wide-containers
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                *loaded? (atom false)]
            (.appendChild (.-body js/document) container)
            (let [root (await! (support/create-root! container))]
              (try
                (doseq [width ["18rem" "40rem"]]
                  (await! (support/render! root
                            [:div
                             [:link {:rel "stylesheet"
                                     :href "/css/tolgraven/modules/github.css"
                                     :on-load #(reset! *loaded? true)}]
                             [:section.github-commits
                              {:style {:width width
                                       :box-sizing "border-box"
                                       :--space "1rem"}}
                              [:div.github-commits-inner
                               (for [text [(str "fix(" (string/join (repeat 32 "x")) "): "
                                                (string/join (repeat 300 "y")))
                                           "scss: theme: preserve category filters"
                                           "Merge pull request #42 from codex/github"
                                           "fix: keep https://example.com:4000/path\n\nNotes: body stays text"]]
                                 (with-meta
                                   [:div.github-commit {:style {:display "flex"}}
                                    [:div.user-avatar]
                                    [:div.github-commit-details
                                     [views/<commit-message> (message/parse-message text)]]]
                                   {:key text}))]]]))
                  (await! (support/wait-for! #(deref *loaded?)))
                  (let [box (.querySelector container ".github-commits-inner")
                        rows (.querySelectorAll container ".github-commit")]
                    (is (<= (.-scrollWidth box) (.-clientWidth box))
                        (str "No horizontal scroll at " width))
                    (is (= 0 (.-length (.querySelectorAll (.item rows 2) ".github-commit-titles")))
                        "An ordinary merge subject has no category tags")
                    (is (= "Notes: body stays text"
                           (last (string/split-lines
                                   (.-textContent (.querySelector (.item rows 3) ".info"))))))))
                (finally
                  (support/unmount! root)
                  (.remove container))))))
        (.catch (fn [error] (is false (str error))))
        (.finally done))))
