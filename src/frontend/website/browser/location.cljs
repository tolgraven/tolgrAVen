(ns website.browser.location
  "Browser URL access shared by the UI and local rendering. Relative URLs use
  the platform parser, including its rules for default ports and escaping.")

(defn origin [] (.-origin js/location))

(defn page-url
  "The current document URL, including its query and excluding its fragment."
  []
  (str (origin) (.-pathname js/location) (.-search js/location)))

(defn local-path [href base-origin]
  (try
    (let [url (js/URL. href base-origin)]
      (when (= base-origin (.-origin url)) (.-pathname url)))
    (catch :default _ nil)))
