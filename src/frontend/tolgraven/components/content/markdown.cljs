(ns tolgraven.components.content.markdown
  "Reusable native Markdown elements for authored content and ordinary site UI."
  (:require [tolgraven.component.registry]
            [tolgraven.macros :refer-macros [defc]]
            [tolgraven.components.image :as image]
            [tolgraven.components.content.media :as media]
            [tolgraven.react :as r]
            [tolgraven.components.highlight :as highlight]))

(defc <heading>
  "A Markdown heading. Level is a number from one to six, and text is its visible label."
  {:authoring {:category "Markdown", :defaults {:level 2, :text "Heading"}}
   :spec-schema [:map [:level [:int {:min 1, :max 6}]] [:text :string]]}
  [{:keys [level text]}] [(keyword (str "h" level)) text])

(defc <paragraph>
  "A Markdown paragraph of plain text."
  {:authoring {:category "Markdown", :defaults {:text "Paragraph"}}
   :spec-schema [:map [:text :string]]}
  [{:keys [text]}] [:p text])

(defc <quote>
  "A Markdown block quotation of plain text."
  {:authoring {:category "Markdown", :defaults {:text "Quotation"}}
   :spec-schema [:map [:text :string]]}
  [{:keys [text]}] [:blockquote [:p text]])

(defc <list>
  "A Markdown bullet or numbered list. Items is a JSON array of strings; ordered selects numbered bullets."
  {:authoring {:category "Markdown", :defaults {:items ["First" "Second"], :ordered false}}
   :spec-schema [:map [:items [:vector :string]] [:?ordered :boolean]]}
  [{:keys [items ordered]}]
  (into [(if ordered :ol :ul)] (map-indexed (fn [index text] ^{:key index} [:li text]) items)))

(defc <tasks>
  "A Markdown task list. Items is a JSON array of objects with text and checked fields. Published tasks display their saved checked state."
  {:authoring {:category "Markdown"
               :defaults {:items [{:text "Write something", :checked false}]}}
   :spec-schema [:map [:items [:vector [:map [:text :string] [:checked :boolean]]]]]}
  [{:keys [items]}]
  [:ul.contains-task-list
   (for [[index {:keys [text checked]}] (map-indexed vector items)]
     ^{:key index} [:li.task-list-item [:input {:type "checkbox", :checked checked, :disabled true}] text])])

(defc <table>
  "A Markdown table. Headers is a JSON array of column labels; rows is an array of arrays of cell text."
  {:authoring {:category "Markdown", :defaults {:headers ["Column"], :rows [["Value"]]}}
   :spec-schema [:map [:headers [:vector :string]] [:rows [:vector [:vector :string]]]]}
  [{:keys [headers rows]}]
  [:table [:thead [:tr (for [[index label] (map-indexed vector headers)] ^{:key index} [:th label])]]
   [:tbody (for [[index cells] (map-indexed vector rows)]
             ^{:key index} [:tr (for [[column text] (map-indexed vector cells)] ^{:key column} [:td text])])]])

(defc <code>
  "A Markdown code block or inline code span rendered by the site's shared deferred syntax highlighter. Language is optional."
  {:authoring {:category "Markdown", :defaults {:text "(+ 1 2)", :language "clojure"}}
   :spec-schema [:map [:text :string] [:?language :string] [:?inline :boolean]]}
  [{:keys [text language inline]}]
  [highlight/<code-block> text {:language language, :inline? (boolean inline)}])

(defc <image>
  "A Markdown image with alternative text. The image component chooses responsive and modern formats when available."
  {:authoring {:category "Markdown", :defaults {:src "/img/tolgrav-square.png", :alt "Image"}}
   :spec-schema [:map [:src :string] [:alt :string]]}
  [{:keys [src] :as spec}]
  (let [assets (r/use-context media/asset-context)
        readiness (r/use-context media/readiness-context)]
    ^{:key (media/preview-key src readiness)}
    [image/<picture> (merge (media/readiness-attrs src readiness)
                            (cond-> (dissoc spec :layout :asset) (get assets src) (assoc :asset (get assets src))))]))

(defc <link>
  "A Markdown link. Supply an HTTPS URL or a local path, and its visible text."
  {:authoring {:category "Markdown", :defaults {:href "/blog", :text "Read the blog"}}
   :spec-schema [:map [:href [:re "^(?:https?://|/[^/]|#[A-Za-z0-9_-])"]] [:text :string]]}
  [{:keys [href text]}] [:a {:href href} text])

(defc <formatted-text>
  "A Markdown text mark: bold, italic, deleted, underlined, highlighted, superscript or subscript. Text remains plain text inside the chosen semantic element."
  {:authoring {:category "Markdown"
               :defaults {:text "Emphasis", :kind "strong"}}
   :spec-schema [:map [:text :string] [:kind [:enum "strong" "em" "del" "u" "mark" "sup" "sub"]]]}
  [{:keys [text kind]}] [(keyword kind) text])

(defc <line-break>
  "A Markdown hard line break within a larger rendered text container."
  {:authoring {:category "Markdown"}
   :spec-schema [:map]}
  [_] [:br])

(defc <rule>
  "A Markdown thematic break separating sections."
  {:authoring {:category "Markdown"}
   :spec-schema [:map]}
  [_] [:hr])
