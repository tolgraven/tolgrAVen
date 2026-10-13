(ns website.media.svg
  "Inspect native vector images without raster conversion or external XML reads."
  (:require [clojure.string :as string]
            [website.media.storage :as storage])
  (:import [java.io ByteArrayInputStream]
           [javax.xml XMLConstants]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Document Element NamedNodeMap Node NodeList]
           [org.xml.sax ErrorHandler]))

(defn- invalid! [] (storage/fail! 400 "Choose a standalone SVG image without active or external content"))

(defn- css-safe? [value]
  ;; Keep ordinary vector styles and local paint-server references. Escaped CSS
  ;; tokens and external resource functions do not belong to a standalone image.
  (let [local-refs (string/replace value #"(?i)url\(\s*['\"]?#[^\s()'\"<>\\]+['\"]?\s*\)" "none")]
    (not (re-find #"(?i)\\|@import|url\s*\(|(?:image-set|image|expression)\s*\(|(?:javascript|https?|file|data):|//" local-refs))))

(defn- safe-reference? [element value]
  (or (string/starts-with? value "#")
      (and (= "a" (.getLocalName ^Element element))
           (re-matches #"(?i)(?:https?://|mailto:|tel:)[^\s<>]+" value))
      (re-matches #"data:image/(?:png|jpeg|webp|avif);base64,[A-Za-z0-9+/]+={0,2}" value)))

(defn- inspect-element! [^Element element]
  (when (#{"script" "foreignobject" "iframe" "object" "embed"}
          (string/lower-case (.getLocalName element)))
    (invalid!))
  (let [^NamedNodeMap attributes (.getAttributes element)]
    (doseq [index (range (.getLength attributes))
            :let [^Node attribute (.item attributes index)
                  name (string/lower-case (or (.getLocalName attribute) (.getNodeName attribute)))
                  value (.getNodeValue attribute)]]
      (when (or (string/starts-with? name "on")
                (and (#{"href" "src"} name) (not (safe-reference? element value)))
                (and (#{"style" "fill" "stroke" "filter" "clip-path" "mask"
                        "marker-start" "marker-mid" "marker-end" "from" "to" "by" "values"} name)
                     (not (css-safe? value)))
                (and (= "attributename" name)
                     (let [target (last (string/split (string/lower-case value) #":"))]
                       (or (#{"href" "src"} target) (string/starts-with? target "on")))))
        (invalid!))))
  (when (and (= "style" (.getLocalName element)) (not (css-safe? (.getTextContent element))))
    (invalid!)))

(defn- length-value [value]
  (when-let [[_ number unit] (re-matches #"\s*([+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*(px|in|cm|mm|pt|pc)?\s*" value)]
    (* (Double/parseDouble number) (case unit "in" 96.0 "cm" (/ 96.0 2.54)
                                       "mm" (/ 96.0 25.4) "pt" (/ 96.0 72.0) "pc" 16.0 1.0))))

(defn- dimensions [^Element root]
  (let [view-box (when (seq (.getAttribute root "viewBox"))
                   (try (mapv #(Double/parseDouble %) (string/split (string/trim (.getAttribute root "viewBox")) #"[\s,]+"))
                        (catch Exception _ nil)))
        box-width (when (= 4 (count view-box)) (nth view-box 2))
        box-height (when (= 4 (count view-box)) (nth view-box 3))
        width (length-value (.getAttribute root "width"))
        height (length-value (.getAttribute root "height"))
        width' (or width (when (and height box-width box-height (pos? box-height))
                           (* height (/ box-width box-height))) box-width)
        height' (or height (when (and width box-height box-width (pos? box-width))
                            (* width (/ box-height box-width))) box-height)]
    (when-not (every? #(and (number? %) (Double/isFinite (double %)) (<= 0.000001 % 65536)) [width' height'])
      (invalid!))
    {:width (long (Math/ceil width'))
     :height (long (Math/ceil height'))}))

(defn inspect!
  "Validate a bounded SVG and return intrinsic dimensions. Accepted source bytes
   remain native SVG; no codec, script, stylesheet, entity or URL is executed."
  [source]
  (when-not (<= 1 (alength ^bytes source) storage/max-source-bytes) (invalid!))
  (try
    (let [factory (doto (DocumentBuilderFactory/newDefaultInstance)
                    (.setNamespaceAware true)
                    (.setXIncludeAware false)
                    (.setExpandEntityReferences false)
                    (.setFeature XMLConstants/FEATURE_SECURE_PROCESSING true)
                    (.setFeature "http://apache.org/xml/features/disallow-doctype-decl" true)
                    (.setFeature "http://xml.org/sax/features/external-general-entities" false)
                    (.setFeature "http://xml.org/sax/features/external-parameter-entities" false)
                    (.setAttribute XMLConstants/ACCESS_EXTERNAL_DTD "")
                    (.setAttribute XMLConstants/ACCESS_EXTERNAL_SCHEMA "")
                    (.setAttribute "http://www.oracle.com/xml/jaxp/properties/maxElementDepth" "128"))
          builder (.newDocumentBuilder factory)
          _ (.setErrorHandler builder (reify ErrorHandler
                                        (warning [_ error] (throw error))
                                        (error [_ error] (throw error))
                                        (fatalError [_ error] (throw error))))
          ^Document document (with-open [input (ByteArrayInputStream. source)] (.parse builder input))
          ^Element root (.getDocumentElement document)
          ^NodeList nodes (.getElementsByTagName document "*")]
      (when-not (and (= "svg" (.getLocalName root))
                     (= "http://www.w3.org/2000/svg" (.getNamespaceURI root)))
        (invalid!))
      (let [^NodeList children (.getChildNodes document)]
        (doseq [index (range (.getLength children))
                :let [^Node node (.item children index)]]
          (when (= Node/PROCESSING_INSTRUCTION_NODE (.getNodeType node)) (invalid!))))
      (doseq [index (range (.getLength nodes))] (inspect-element! (.item nodes index)))
      (dimensions root))
    (catch Exception _ (invalid!))))
