(ns website.github-test
  (:require-macros [website.test-async :refer [go-promise await!]])
  (:require [cljs.core.async]
            [cljs.test :refer-macros [async deftest is testing]]
            [clojure.string :as string]
            [malli.core :as m]
            [website.schema.dsl :as dsl]
            [website.modules.github.message :as message]
            [website.modules.github.schema :as schema]
            [website.modules.github.views :as views]
            [website.modules.github.events]
            [website.modules.github.subs]
            [website.components.activity :as activity]
            [website.component.data :as data]
            [website.loader.styles :as styles]
            [website.events]
            [website.subs]
            [website.react :as r]
            [website.test-support :as support]))

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
        (is (m/validate (dsl/->malli schema/parsed-message) parsed))))))

(deftest missing-commit-message-remains-renderable
  (let [parsed (message/parse-message nil)]
    (is (m/validate (dsl/->malli [:cat schema/parsed-message [:? :?string]]) [parsed nil]))
    (is (string/includes? (r/render-to-static-markup [views/<commit-message> parsed nil])
                          "title=\"\""))))

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
                                     :href "/css/website/modules/github.css"
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
                                     [views/<commit-message> (message/parse-message text) text]]]
                                   {:key text}))]]]))
                  (await! (support/wait-for! #(deref *loaded?)))
                  (let [box (.querySelector container ".github-commits-inner")
                        rows (.querySelectorAll container ".github-commit")]
                    (is (<= (.-scrollWidth box) (.-clientWidth box))
                        (str "No horizontal scroll at " width))
                    (doseq [row (array-seq rows)]
                      (let [message (.querySelector row ".github-commit-message")
                            info (.querySelector message ".info")
                            labels (.querySelector message ".github-commit-titles")
                            line-height (js/parseFloat (.-lineHeight (js/getComputedStyle info)))
                            row-style (js/getComputedStyle row)
                            padding-left (js/parseFloat (.-paddingLeft row-style))
                            padding-right (js/parseFloat (.-paddingRight row-style))
                            ;; The display reporter can move this fixture as it
                            ;; prints passing assertions above it. Read related
                            ;; native boxes together before reporting any result.
                            row-rect (.getBoundingClientRect row)
                            info-rect (.getBoundingClientRect info)
                            labels-rect (when labels (.getBoundingClientRect labels))
                            label-rects (when labels
                                          (mapv #(.getBoundingClientRect %)
                                                (array-seq (.querySelectorAll labels ":scope > span"))))]
                        (is (pos? padding-left)
                            "Each row has space between its content and the left border")
                        (is (pos? padding-right)
                            "Each row has space between its content and the right border")
                        (is (<= (.-height info-rect) (+ 1 (* 3 line-height)))
                            "Commit text occupies at most three lines")
                        (when labels
                          (is (<= (js/Math.abs (- (.-top labels-rect) (.-top info-rect))) 1)
                              "Labels share the message's top row")
                          (is (>= (.-left labels-rect) (.-right info-rect))
                              "Labels stay to the right of the text")
                          (is (< (.-right labels-rect) (.-right row-rect))
                              "Labels remain inset from the right border")
                          (doseq [label-rect label-rects]
                            (is (>= (.-left label-rect) (.-right info-rect))
                                "Both category and sublabel stay in the right column")))))
                    (is (= "theme" (.-textContent (.querySelector (.item rows 1) ".subtitle"))))
                    (is (= "scss" (.-textContent (.querySelector (.item rows 1) ".title"))))
                    (let [long-info (.querySelector (.item rows 0) ".info")]
                      (is (> (.-scrollHeight long-info) (.-clientHeight long-info))
                          "A long message is clipped while its full text remains intact"))
                    (is (= (str "fix(" (string/join (repeat 32 "x")) "): "
                                (string/join (repeat 300 "y")))
                           (.getAttribute (.querySelector (.item rows 0) ".github-commit-message") "title"))
                        "Hover retains the original full subject, including its prefix")
                    (is (= "fix: keep https://example.com:4000/path\n\nNotes: body stays text"
                           (.getAttribute (.querySelector (.item rows 3) ".github-commit-message") "title"))
                        "The complete multiline message remains available on hover")
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

(deftest absent-settings-are-pending-until-an-empty-response-arrives
  (let [subscribe (r/subscribe-function)
        *configuration (r/atom nil)]
    (r/with-framework-bindings
      [r/subscribe (fn
                     ([query]
                      (case (first query)
                        :github/configuration-result *configuration
                        :github/get-from (r/atom [nil nil])
                        (subscribe query)))
                     ([query options] (subscribe query options)))]
      (let [pending (r/render-to-static-markup [views/<commits>])]
        (is (string/includes? pending "aria-busy=\"true\""))
        (is (string/includes? pending "Loading repository settings…"))
        (is (not (string/includes? pending "loading-spinner")))
        (is (not (string/includes? pending "Configure a GitHub"))))
      (reset! *configuration {:settings nil})
      (let [empty (r/render-to-static-markup [views/<commits>])]
        (is (string/includes? empty "Configure a GitHub"))
        (is (not (string/includes? empty "loading-spinner")))))))

(deftest configured-repository-rejects-old-responses
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "first-user", :repo "first-repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (is (some #(= {:user "first-user", :repo "first-repo", :q ""} (:url-params %)) @*requests))
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "second-user", :repo "second-repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (r/dispatch-sync [:github/save-commits "first-user" "first-repo" 1 1 {:commits [], :total 99, :complete false}])
            (r/dispatch-sync [:github/save-commit "first-user" "first-repo" 1 "old" {:sha "old"}])
            (is (= {} (await! (support/state-at! [:content :github]))))
            (is (= ["second-user" "second-repo"] (await! (support/subscription-value! [:github/get-from]))))
            (is (= "https://github.com/second-user/second-repo" (await! (support/subscription-value! [:github/website-url]))))
            (r/dispatch-sync [:github/save-commits "second-user" "second-repo" 2 1 {:commits [], :total 0, :complete true}])
            (is (= 0 (await! (support/subscription-value! [:github/commit-count])))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest failed-initial-repository-reads-can-retry
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "retry-user", :repo "repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (is (= 1 (count @*requests)) "One archive response supplies both rows and the full count")
            (doseq [request @*requests]
              (r/dispatch-sync (conj (:on-failure request) {:status 503, :status-text "Unavailable"})))
            (await! (support/settle!))
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "A failed initial archive read retries")
            (doseq [request (drop 1 @*requests)]
              (r/dispatch-sync (conj (:on-success request)
                                     {:commits [], :total 0, :complete true})))
            (await! (support/settle!))
            (is (= [1] (vec (:pages-fetched (await! (support/state-at! [:state :github]))))))
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "Successfully loaded empty pages are retained"))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest repository-generation-rejects-pending-responses-after-switching-back
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (doseq [user ["generation-a" "generation-b" "generation-a"]]
              (r/dispatch-sync [:component-data/install [:module :github :configuration]
                                {:settings {:user user, :repo "repo"}}])
              (r/dispatch-sync [:github/init])
              (await! (support/settle!)))
            (is (= 3 (count @*requests)))
            (let [commits [{:sha "fresh"
                            :commit {:message "fix: current generation"
                                     :author {:date "2026-10-12T12:00:00Z"}}}]]
              (doseq [request (take-last 1 @*requests)]
                (r/dispatch-sync (conj (:on-success request)
                                       {:commits commits, :total 1, :complete true})))
              (await! (support/settle!))
              (doseq [request (take 2 @*requests)]
                (r/dispatch-sync (conj (:on-success request)
                                       {:commits commits, :total 99, :complete false})))
              (await! (support/settle!))
              (is (= ["fresh"] (mapv :sha (await! (support/subscription-value! [:github/commits])))))
              (is (= 1 (await! (support/state-at! [:content :github :total]))))
              (is (= [1] (vec (:pages-fetched (await! (support/state-at! [:state :github]))))))))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest calendar-preserves-real-counts-and-fits-a-phone
  (async done
    (-> (go-promise
          (let [container (.createElement js/document "div")
                days [{:date "2026-10-11", :count 0, :level 0}
                      {:date "2026-10-12", :count 42, :level 4}]]
            (.appendChild (.-body js/document) container)
            (let [root (await! (support/create-root! container))]
              (try
                (await! (support/render! root
                          [:div {:style {:width "18rem"}}
                           [:link {:rel "stylesheet", :href "/css/website/modules/github.css"}]
                           [activity/<calendar> days]]))
                (is (= 2 (.-length (.querySelectorAll container "[data-date]"))))
                (is (= "42 contributions on 2026-10-12"
                       (.getAttribute (.querySelector container "[data-date='2026-10-12']") "aria-label")))
                (is (string/includes? (.-textContent container) "42 contributions"))
                (finally (support/unmount! root) (.remove container))))))
        (.catch #(is false (str %)))
        (.finally done))))

(deftest cached-calendar-belongs-to-its-user-after-switching-back
  (async done
    (let [restore! (r/make-restore-fn)
          original-source (get @data/*sources :url)
          resources (set (mapcat views/activity-dependencies ["activity-test-a" "activity-test-b"]))
          *requests (atom [])
          container (.createElement js/document "div")]
      (data/invalidate! resources)
      (data/register-source! :url
        {:load! (fn [{:keys [url]}]
                  (swap! *requests conj url)
                  (js/Promise.resolve
                    {:days [{:date "2026-10-11"
                             :count (if (string/ends-with? url "activity-test-a") 3 7)
                             :level 1}]}))})
      (.appendChild (.-body js/document) container)
      (-> (go-promise
            (let [root (await! (support/create-root! container))]
              (try
                (doseq [[user total] [["activity-test-a" 3] ["activity-test-b" 7] ["activity-test-a" 3]]]
                  (await! (support/render! root (with-meta [views/<activity> user] {:key user})))
                  (await! (support/wait-for!
                            #(and (string/includes? (.-textContent container) (str user " on GitHub"))
                                  (string/includes? (.-textContent container) (str total " contributions")))))
                  (is (= (str total " contributions on 2026-10-11")
                         (.getAttribute (.querySelector container "[data-date]") "aria-label"))))
                (is (= 2 (count @*requests)) "Returning to A uses A's cached calendar")
                (finally (support/unmount! root)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (data/invalidate! resources)
                      (data/register-source! :url original-source)
                      (.remove container)
                      (restore!)
                      (done)))))))

(deftest calendar-popup-hover-focus-and-cleanup
  (async done
    (let [container (.createElement js/document "div")
          *loaded? (atom false)
          detail (fn [date] [:ul [:li (str "3 commits on " date)]])]
      (.appendChild (.-body js/document) container)
      (-> (go-promise
            (let [root (await! (support/create-root! container))]
              (try
                (await! (support/render! root
                          [:div {:style {:width "18rem"}}
                           [:link {:rel "stylesheet"
                                   :href "/css/website/modules/github.css"
                                   :on-load #(reset! *loaded? true)}]
                           [activity/<calendar> [{:date "2026-10-11", :count 0, :level 0}
                                                 {:date "2026-10-12", :count 3, :level 1}] [detail]]]))
                (await! (support/wait-for! #(true? @*loaded?)))
                (let [day (.querySelector container "[data-date='2026-10-12']")
                      empty-day (.querySelector container "[data-date='2026-10-11']")]
                  (is (nil? (.querySelector js/document ".activity-calendar__popup")))
                  (.dispatchEvent day (js/PointerEvent. "pointerover" #js {:bubbles true}))
                  (await! (support/wait-for! #(.querySelector js/document ".activity-calendar__popup")))
                  (let [popup (.querySelector js/document ".activity-calendar__popup")
                        rect (.getBoundingClientRect popup)]
                    (is (string/includes? (.-textContent popup) "3 commits on 2026-10-12"))
                    (is (= (.getAttribute day "aria-describedby") (.-id popup)))
                    (let [edge (js/parseFloat (.-fontSize (js/getComputedStyle (.-documentElement js/document))))]
                      (is (>= (.-left rect) (dec edge)))
                      (is (<= (.-right rect) (inc (- (.-innerWidth js/window) edge))))))
                  (.dispatchEvent day (js/PointerEvent. "pointerout" #js {:bubbles true}))
                  (await! (support/wait-for! #(nil? (.querySelector js/document ".activity-calendar__popup"))))
                  (.focus day #js {:preventScroll true})
                  (await! (support/wait-for! #(.querySelector js/document ".activity-calendar__popup")))
                  (.dispatchEvent day (js/KeyboardEvent. "keydown" #js {:key "ArrowUp", :bubbles true}))
                  (await! (support/settle!))
                  (is (identical? empty-day (.-activeElement js/document)) "Arrow keys navigate the single-tab-stop grid")
                  (.focus day #js {:preventScroll true})
                  (.dispatchEvent day (js/KeyboardEvent. "keydown" #js {:key "Escape", :bubbles true}))
                  (await! (support/wait-for! #(nil? (.querySelector js/document ".activity-calendar__popup"))))
                  (.focus empty-day #js {:preventScroll true})
                  (await! (support/wait-for! #(.querySelector js/document ".activity-calendar__popup")))
                  (is (not (string/includes? (.-textContent (.querySelector js/document ".activity-calendar__popup")) "commits on")))
                  (.blur empty-day)
                  (await! (support/wait-for! #(nil? (.querySelector js/document ".activity-calendar__popup")))))
                (finally (support/unmount! root) (.remove container)))))
          (.catch #(is false (str %)))
          (.finally done)))))

(deftest search-queries-complete-history-and-rejects-stale-responses
  (async done
    (let [restore! (r/make-restore-fn)
          *requests (atom [])
          *delayed (atom [])]
      (r/reg-fx :http-xhrio #(swap! *requests conj %))
      (r/reg-fx :dispatch-later #(swap! *delayed into %))
      (-> (go-promise
            (r/dispatch-sync [:init/app-db])
            (r/dispatch-sync [:component-data/install [:module :github :configuration]
                              {:settings {:user "test-user", :repo "repo"}}])
            (r/dispatch-sync [:github/init])
            (await! (support/settle!))
            (r/dispatch-sync [:github/search "nested theme"])
            (r/dispatch-sync [:github/search "archive"])
            (doseq [{:keys [dispatch]} @*delayed] (r/dispatch-sync dispatch))
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "Only the current debounced query is acquired")
            (is (= "archive" (get-in (last @*requests) [:url-params :q])))
            (is (= "archive" (await! (support/subscription-value! [:form-field [:github :search]]))))
            (r/dispatch-sync (conj (:on-success (first @*requests)) {:commits [], :total 999, :complete false}))
            (r/dispatch-sync (conj (:on-success (last @*requests)) {:commits [], :total 0, :complete true}))
            (await! (support/settle!))
            (is (= 0 (await! (support/subscription-value! [:github/commit-count]))))
            (r/dispatch-sync [:github/fetch-commits-next "test-user" "repo"])
            (await! (support/settle!))
            (is (= 2 (count @*requests)) "Exhausted searches do not request extra pages"))
          (.catch #(is false (str %)))
          (.finally (fn [] (restore!) (done)))))))

(deftest failed-day-detail-stays-compact-and-truthful
  (async done
    (let [restore! (r/make-restore-fn)
          original-source (get @data/*sources :url)
          resources (set (views/day-dependencies "detail-failure-test" "2026-10-12"))
          *requests (atom [])
          container (.createElement js/document "div")]
      (data/invalidate! resources)
      (data/register-source! :url {:load! (fn [{:keys [url]}]
                                          (swap! *requests conj url)
                                          (js/Promise.reject (js/Error. "Token unavailable")))})
      (.appendChild (.-body js/document) container)
      (-> (go-promise
            (let [root (await! (support/create-root! container))]
              (try
                ;; The portal needs its own native stylesheet; the preceding
                ;; case releases its local link on unmount.
                (await! (styles/acquire! :github))
                ;; Keep the focus-owned anchor independent of report reflow.
                (await! (support/render! root
                          [:div {:style {:position "fixed", :top "0", :width "18rem"}}
                           [activity/<calendar>
                            [{:date "2026-10-12", :count 42, :level 4}]
                            [views/<day-details> "detail-failure-test"]]]))
                (let [requests-before (count @*requests)]
                  ;; The display reporter can still be smoothly scrolling from
                  ;; the preceding case. End that test-owned scroll and drain
                  ;; its native events before opening a scroll-dismissed tooltip.
                  (.scrollTo js/window #js {:top (.-scrollY js/window), :behavior "instant"})
                  (await! (support/settle!))
                  (.focus (.querySelector container "[data-date]") #js {:preventScroll true})
                  (await! (support/wait-for! #(.querySelector js/document ".activity-calendar__popup")))
                  (let [position (.-position (js/getComputedStyle
                                              (.querySelector js/document ".activity-calendar__popup")))]
                    (await! (support/wait-for! #(some-> (.querySelector js/document ".activity-calendar__popup")
                                                               .-textContent
                                                               (string/includes? "Breakdown temporarily unavailable."))))
                    ;; Capture together before assertion output reflows the
                    ;; report and correctly dismisses the native popup again.
                    (let [popup (.querySelector js/document ".activity-calendar__popup")
                          text (.-textContent popup)
                          requests-after (count @*requests)
                          control-count (.-length (.querySelectorAll popup "button, details, summary"))]
                      (is (zero? requests-before) "No day requests before user intent")
                      (is (= "fixed" position) "The native tooltip portal stays out of document flow")
                      (is (string/includes? text "42 contributions"))
                      (is (= 1 requests-after))
                      (is (zero? control-count)))))
                (finally (support/unmount! root) (.remove container)))))
          (.catch #(is false (str %)))
          (.finally (fn []
                      (data/register-source! :url original-source)
                      (data/invalidate! resources)
                      (restore!)
                      (done)))))))

(deftest keyboard-calendar-navigation-reveals-offscreen-days-without-page-scroll
  (async done
    (let [container (.createElement js/document "div")
          days (mapv (fn [index]
                       {:date (subs (.toISOString (js/Date. (+ (.parse js/Date "2026-01-01") (* index 86400000)))) 0 10)
                        :count 0
                        :level 0}) (range 210))]
      (.appendChild (.-body js/document) container)
      (-> (go-promise
            (let [root (await! (support/create-root! container))]
              (try
                (await! (support/render! root
                          [:div {:style {:position "fixed", :top "1rem", :left "1rem", :width "18rem"}}
                           [:link {:rel "stylesheet", :href "/css/website/modules/github.css"}]
                           [activity/<calendar> days]]))
                (let [first-day (.querySelector container "[data-date]")
                      last-day (last (array-seq (.querySelectorAll container "[data-date]")))
                      scroller (.querySelector container ".activity-calendar__scroll")
                      page-y (.-scrollY js/window)]
                  (.focus first-day #js {:preventScroll true})
                  (.dispatchEvent first-day (js/KeyboardEvent. "keydown" #js {:key "End", :bubbles true}))
                  (await! (support/settle!))
                  (let [target-rect (.getBoundingClientRect last-day)
                        scroll-rect (.getBoundingClientRect scroller)]
                    (is (identical? last-day (.-activeElement js/document)))
                    (is (>= (.-left target-rect) (.-left scroll-rect)))
                    (is (<= (.-right target-rect) (inc (.-right scroll-rect))))
                    (is (= page-y (.-scrollY js/window))))
                  (is (= 1 (.-length (.querySelectorAll container "[data-date][tabindex='0']")))))
                (finally (support/unmount! root) (.remove container)))))
          (.catch #(is false (str %)))
          (.finally done)))))
