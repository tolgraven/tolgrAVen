(ns tolgraven.db
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [cljs.reader]))

(declare data)

(rf/reg-event-db :init-db [rf/debug]
  (fn [db _] data))

#_(defn init "Initialize the database. Only once unless forced..."
 [& force?]
 #_(defonce _init (rf/dispatch-sync [:init-db]))
 #_(rf/clear-subscription-cache!)) ;

; maybe call <- and -> etc, symbols would be nice, plus minus get/set warnings hehe
; issue anyways is often start with this then need to change to doing it properly...
(defn <-db "Quick subscription getter. Bad habit I guess..."
 [& path] ;default is better handled ext since this goes through :get -> we already know there is a valid sub
 (let [path (if (seqable? (first path)) (first path) path) ;handle both individual args and vector
       sub (rf/subscribe (into [:get] path))]
  @sub))

(defn ->db "Quick db setter. Returns passed value."
 [path value]
 (rf/dispatch [:set path value])
 value)

(defn setter "Quick db setter fn getter"
 [path]
 (fn [value & _] (->db path value)))

(defn toggle "Toggle bool at path in db"
 [path]
 (rf/dispatch [:toggle path]))


; localstorage i took from somewhere prob not useful here
(def user-key "tolglow-web-dev-storage")  ;; localstore key

(defn set-user-ls [user]
  (.setItem js/localStorage user-key (str user)))  ;; sorted-map written as an EDN map

(defn remove-user-ls  []
  (.removeItem js/localStorage user-key))

(rf/reg-cofx :local-store-user
 (fn [cofx _]
   (assoc cofx :local-store-user  ;; put the local-store user into the coeffect under :local-store-user
          (into (sorted-map)      ;; read in user from localstore, and process into a sorted map
                (some->> (.getItem js/localStorage user-key)
                         (cljs.reader/read-string))))))  ;; EDN map -> map

(def data ; default db
 {; :filter {:method "regex"}
  ; :view {:page [0 0] :offset [0 0] :size 8}
 :content {:logo-header ["tolgrAVen" ["audio" "visual"]]
           :logo-bg "img/tolgrav.png"
           :intro-text ["Building experience"
                   "Is what it all comes down to.
                    So many roads, yet I'm determined to track most of them halfway down.
                    There's only one way to find out what's possible blabla copy."]

           :services-text  [["Audio" "bullhorn"   ["Sound engineering" "Broadcasting" "Recording" "Music production"]]
                       ["Video" "film"       ["Editing" "Production" "Shoot inna pinch"]]
                       ["Stage" "lightbulb"  ["Event production" "Concert" "Club" "Automation & Retrofitting"]]
                       ["Design" "joint"     ["I mean not really" "But sorta dig the logo" "Not like I'm qualified" "For anything else either"]]
                       ["Software" "code"    ["Open source (evtly)" "Bespoke projects" "Rapid prototyping" "From (cl)JS to C++" "Current focus"]]
                       ["Web" "laptop"       ["This is my first website!" "I'll make more once" "my stack runs fulleth"]]]
           :interlude []
           }

 :schema {:query
          {:fixtures {:query "{ fixtures { name id key x y z } }"
                      :event [:tolglow-web.events/save-fixtures]}
           :shaders {:query "query GetShader($path: String) {
                               shaders(path: $path) {
                                 file_name kind text
                             } }"
                     :args {:path "resources/public/glsl"}
                     :event [:save-shaders]}}
          :subscription
          {:fixture-state {:query "{ fixture_state { id color dimmer strobe pan_tilt lookat } }"
                           :event [:save-fixture-state]}
           :live-shaders {:query "{ live_shader { file_name text kind }}"
                          :event [:save-shaders]}}}
 :options {:auto-save-vars true
           :hud {:timeout 30 :level :info}}})

