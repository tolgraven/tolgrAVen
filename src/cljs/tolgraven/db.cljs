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
 {:content {:header {:text ["tolgrAVen" ["audio" "visual"]]
                    :menu {:work  [["Services"  "#link-services"] ; should have two sections either by collapse
                                   ["Story"     "#about"]         ; or just opp sides with the /
                                   ["Tools"     "#tools"]
                                   ["Portfolio" "#portfolio"]]
                           :personal [["Blog"      "#/blog"]
                                      ["Log"       "#/log"]]}
                    #_{:should :semi-auto-populate?}}
           :intro {:title "Building experience"
                   :text "Is what it all comes down to.
                    So many roads, yet I'm determined to track most of them halfway down.
                    There's only one way to find out what's possible blabla copy."
                   :buttons  [["Join me"            "bottom"]
                              ["Fix these buttons"  "linktotop"]]
                   :bg {:src "img/foggy-shit-small.jpg" :alt "Purple enthusiast"}
                   :logo-bg "img/tolgrav.png"} ; no :src cause goes in :style background-image...

           :services  {:categories
                       [["Audio"    "bullhorn"   ["Sound engineering" "Broadcasting" "Recording" "Music production"]]
                        ["Video"    "film"       ["Editing" "Production" "Shoot inna pinch"]]
                        ["Stage"    "lightbulb"  ["Event production" "Concert" "Club" "Automation & Retrofitting"]]
                        ["Design"   "joint"      ["I mean not really" "But sorta dig the logo" "Not like I'm qualified" "For anything else either"]]
                        ["Software" "code"       ["Open source (evtly)" "Bespoke projects" "Rapid prototyping" "From (cl)JS to C++" "Current focus"]]
                        ["Web"      "laptop"     ["This is my first website!" "I'll make more once" "my stack runs fulleth"]]]
                       :bg {:src "img/vim-code-small.jpg" :alt "neovim editor"}
                       :caption "neovim"}

           :moneyshot {:title "YOU"
                       :caption "Hapy people enjoying haspitality blobli"
                       :bg {:src "img/crowd-lbp.JPG"}}

           :story {:heading {:title "Breaking things down"
                              :bg {:src "img/wide-spot-ctrl-small.jpg"}}
                   :title "Butt your trust in my flaky hands"
                   :text "1 I'm not actually very good at anything so...
                          2 I'm not actually very good at anything so my services are cheap as or not available.
                          3But as you can see above I know a lot of different stuff.  Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          4Learned a ton, is what's truly important. But as you can see above I know a lot of different stuff.
                          5Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          6Learned a ton, is what's truly important.
                          7But as you can see above I know a lot of different stuff.
                          8Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well."
                   :images [["headshot" {:src "img/Leg-tasteful-small.jpg" :alt "Joen"}
                             "Yours truly. Smiling!"]
                            ["multimeter" {:src "img/multimeter-driver-small.jpg" :alt "Broken driver"}
                             "Something smells fishy here. Sorry I mean burnt"
                             "right"]]}

           :interlude [{:title "What does that mean?"
                         :caption "Stage light blabla"
                         :bg [:video.media.media-as-bg {:src "media/fog-3d-small.mp4"}]}
                        {:title "For who?"
                         :caption "Nihiloxica video shoot, Kampala"
                         :bg [:video.media.media-as-bg {:src "media/nihil-shoot2.mp4"}]}
                        {:title "Got no portfolio"
                         :caption "Failed"
                         :bg [:img.media.media-as-bg.fade-3 {:src "img/collage-strips.jpg"}]}]

           :gallery [{:src "img/joen-mixer.jpg" :alt "My actual first web project"}
                     {:src "img/live-session-small.jpg" :alt "Ableton Live"}
                     {:src "img/ssiri-balcony-small.jpg" :alt "Some nice people"}
                     {:src "img/video-editing-small.jpg" :alt "Television"}
                     {:src "img/afterglow-new-web-old-small.jpg" :alt "My actual first web project"}]

           :blog    [{:id 1 :title "And so it all begins" :md "[i forget] how does *markdown* work??"}
                     {:id 2 :title "My journey into wankery" :md "jk i never _knew_"}]

           :footer [{:id "left"
                     :title "Made w/ hiccup n shit"
                     :text ["joen.tolgraven@gmail.com"
                            "© 2019-2020"]}
                    {:id "middle"
                     :title "Whatever"
                     :text ["other stuff one could write"
                            "if so fucking inclined"]}
                    {:id "right"
                     ; :title "More ways to get in touch"
                     :links [{:name "Github" :href "https://github.com/tolgraven" :icon "github"}
                             {:name "Soundcloud" :href "https://soundcloud.com/tolgraven" :icon "soundcloud"}]}]

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

