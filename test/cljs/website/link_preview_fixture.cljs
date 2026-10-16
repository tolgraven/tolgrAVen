(ns website.link-preview-fixture
  "Manual browser fixture using real preview components, isolated from Firebase."
  (:require [website.react :as r]
            [website.component.data :as data]
            [website.modules.link-preview.article :as article]
            [website.validation :as validation]

            [website.modules.link-preview.module]
            [website.modules.link-preview.views :as preview]
            [website.macros :refer-macros [defc defpage]]))

(defonce *root (atom nil))
(defonce *fullscreen? (r/atom false))

(defc <preview-link> {:features [:props :links]} [spec url]
  [:p [:a {:href url} "Open the local preview page"]])

(defn <fixture> []
  (let [url (js/URL. "/preview-target.html" js/window.location.href)
        _ (set! (.-hostname url) (if (= "localhost" (.-hostname url)) "127.0.0.1" "localhost"))
        href (str url)]
    [:<>
     [:div.header-before]
     [:div.fill-side-top [:div {:class (when @*fullscreen? "adjust-for-fullscreen")}]]
     [:header [:h2 "tolgrAVen"]
      [:button {:on-click #(swap! *fullscreen? not)} "Toggle fullscreen framing"]]
     [:main.main-content {:style {:padding "2rem" :min-height "120vh"}}
      [:h1 "Link preview fixture"]
      [:p "Hover or focus the link to preview. Click the preview to navigate, then use Back."]
      [<preview-link> {:links {:id "fixture-link" :text href :trust :untrusted}} href]
      [:p [:a {:href href :target "_blank"} "Open directly in a new tab"]]
      [:p {:style {:margin-top "65vh"}} "Scroll to verify header and footer framing."]]
     [:footer.footer-sticky {:class (when @*fullscreen? "adjust-for-fullscreen")}
      [:div.footer-content [:p "Local browser fixture · no remote services"]]]
     [preview/<link-preview>]]))

(defn ^:export init []
  (when-not @*root
    (reset! *root (r/create-root (.getElementById js/document "app"))))
  (r/render @*root [<fixture>]))

;; Visual captures use the same content feature, managed read and body portal as
;; the site. The manual page above remains available for real navigation checks.
(def visual-url "https://example.invalid/native-preview")
(defonce *visual-reads (atom []))

(defn visual-result [kind]
  (cond-> {:status "ready"
           :url visual-url
           :title "Native readable preview"
           :description "External content keeps the site's typography and native preview controls."
           :blocks []
           :truncated? false
           :frame-policy "blocked"}
    (= kind "link-preview-ready")
    (assoc :image "/img/tolgrav-square.png"
           :blocks [{:kind "heading", :level 1, :text "Article section"}
                    {:kind "paragraph", :text "Readable paragraphs wrap within the original anchored surface."}
                    {:kind "quote", :text "Keep the original spacing and quote treatment."}
                    {:kind "code", :text "<script>literal preview text</script>"}
                    {:kind "item", :text "A native article item"}]
           :truncated? true)
    (= kind "link-preview-unavailable")
    (assoc :status "unavailable"
           :description "This page cannot supply a readable preview. Open the link to continue.")))

(defn install-visual! [kind]
  (let [result (visual-result kind)
        expected-url (:url (preview/article-dependency visual-url))]
    (when (validation/explain article/result result)
      (throw (js/Error. "Invalid readable link preview visual response")))
    (reset! *visual-reads [])
    (data/register-source! :url
      {:load! (fn [{:keys [url]}]
                (swap! *visual-reads conj url)
                (if (= expected-url url)
                  (js/Promise.resolve result)
                  (js/Promise.reject (js/Error. "Unexpected readable preview fixture read"))))})))

(defn ^:export visual-read-count [] (count @*visual-reads))

(defpage <visual-page> []
  [:section
   [:h1 "Readable link previews"]
   [<preview-link> {:links {:id "visual-readable-preview"
                           :text visual-url
                           :trust :untrusted}}
    visual-url]])

(defn <visual-fixture> []
  [:<>
   [:main#main.main-content.perspective-top [<visual-page>]]
   [preview/<link-preview>]])
