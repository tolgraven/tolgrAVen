(ns website.browser.css
  "Read CSSOM numbers at the browser boundary.")

(defn magnitude
  "Read the numeric prefix of a CSS value like 12px or 0.3s. Keeps the unit
   conversion with the caller and yields NaN for keywords such as auto."
  [value]
  (js/parseFloat value))

(defn font-size
  "Computed font size in CSS pixels. With no element, return the size of one rem."
  ([] (font-size (.-documentElement js/document)))
  ([element] (magnitude (.-fontSize (js/getComputedStyle element)))))
