(ns website.loader-view-ssr-styles-test
  (:require [clojure.test :refer [deftest is]]
            [website.layout :as layout]))

(deftest visible-lightweight-export-adds-its-owner-and-transitive-styles
  (let [manifest {:blog ["/blog.css"]
                  :markdown ["/markdown.css"]
                  :user ["/user.css"]
                  :home ["/home.css"]
                  :carousel ["/carousel.css"]
                  :maps ["/maps.css"]}
        request {:uri "/blog/post/17"}
        before (layout/initial-styles request manifest)
        rendered (assoc request :ssr {:snapshot {:module-views {:home [:float-image]}}})
        after (layout/initial-styles rendered manifest)]
    (is (not-any? #{"/home.css" "/carousel.css"} before)
        "A blog without the authored export acquires neither optional owner")
    (is (some #{"/home.css"} after))
    (is (some #{"/carousel.css"} after) "The visible owner retains its declared style dependency")
    (is (not (some #{"/maps.css"} after)) "Unrendered owners stay deferred")
    (is (= 1 (count (filter #{"/user.css"} after))))))

(deftest home-does-not-load-closed-account-or-reader-styles
  (let [manifest {:home ["/home.css"]
                  :carousel ["/carousel.css"]
                  :user ["/user.css"]
                  :blog ["/blog.css"]
                  :markdown ["/markdown.css"]}
        request {:uri "/"}
        paths (layout/initial-styles request manifest)]
    (is (= #{"/home.css" "/carousel.css"} (set paths)))
    (is (some #{"/user.css"}
              (layout/initial-styles
                (assoc request :ssr {:snapshot {:module-views {:user [:view]}}}) manifest))
        "An actually rendered account panel still receives its styles before hydration")))
