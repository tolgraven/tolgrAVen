(ns tolgraven.db
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [cljs.reader]))


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


; localstorage i took from somewhere, maybe use for partly written comments
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
  {:state {:menu false
           :is-loading true
           :theme-force-dark true
           :is-personal false
           :debug {:layers false}
           ; :transition :out ;later when proper boot sequence, trigger in on load complete
           }
   :routes {:home "/"
            :about "/about"
            :docs "docs"
            :blog "/blog"
            :log "log"}
   :users [{:id 0 :name "tolgraven" :password "js roles v secure" :roles [:admin :blogger]}
           {:id 1 :name "seqable" :password "fixme" :roles [:blogger]}
           {:id 2 :name "erty" :password "burkimaten"}]
   :content {:header {:text ["tolgrAVen" ["audio" "visual"]]
                      :text-personal ["tolgraven" ["" "joen"]]
                      :menu {:work  [["Services"  "#link-services" :services] ; should have two sections either by collapse
                                     ["Story"     "#about"        :about]         ; or just opp sides with the /
                                     ["Tools"     "#tools"        :tools]
                                     ["Portfolio" "#portfolio"    :portfolio]]
                             :personal [["Blog"      "#/blog"     :blog]
                                        ["Docs"      "#/docs"     :docs]
                                        ["Log"       "#/log"      :log]]}
                      ; #_{:should :semi-auto-populate?}
                      }
             :intro {:title "Building experience"
                     :text "Is what it all comes down to.
                            So many roads, yet I'm determined to track most of them halfway down.
                            There's only one way to find out what's possible blabla copy."
                     :buttons  [["Join me"            "bottom"]
                                ["Fix these buttons"  "linktotop"]]
                     :bg [{:src "img/foggy-shit-small.jpg" :alt "Purple enthusiast"}
                          {:src "img/live-session-small.jpg" :alt "Ableton Live"}
                          {:src "img/afterglow-new-web-old-small.jpg" :alt "My actual first web project"}]
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
                     :title "Put your trust in my flaky hands"
                     :text "1 I'm not actually very good at anything so...
                          2 I'm not actually very good at anything so my services are cheap as or not available.
                          3But as you can see above I know a lot of different stuff.  Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          4Learned a ton, is what's truly important. But as you can see above I know a lot of different stuff.
                          5Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          6Learned a ton, is what's truly important.
                          7But as you can see above I know a lot of different stuff.
                          8Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          9Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          10Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          11Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          12Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          13 I'm not actually very good at anything so my services are cheap as or not available.
                          14But as you can see above I know a lot of different stuff.  Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          15Learned a ton, is what's truly important. But as you can see above I know a lot of different stuff.
                          16Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          17Learned a ton, is what's truly important.
                          18 I'm not actually very good at anything so my services are cheap as or not available.
                          19But as you can see above I know a lot of different stuff.  Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          20Learned a ton, is what's truly important. But as you can see above I know a lot of different stuff.
                          21Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          22Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          23 I'm not actually very good at anything so my services are cheap as or not available.
                          24But as you can see above I know a lot of different stuff.  Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well.
                          25Learned a ton, is what's truly important. But as you can see above I know a lot of different stuff.
                          26Not sure what happened with this assignment (apart from my laptop dying... weeks ago) and assume there's no salvaging being this late. Oh well."
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
                          :bg [:img.media.media-as-bg.fade-3 {:src "img/collage-strips.jpg"}]}
                         {:title "Placeholder"
                          :caption "Fix this shite"
                          ; :bg [:div.media-as-bg.anim-gradient-bg]}
                          :bg [:img.media.media-as-bg.anim-gradient-bg {:src "img/wide-spot-ctrl-small.jpg"}]}]

             :article-links [{:title "A link thingy"    ;fix autograbber!
                              :text "maybe line or two" ;fix autograbber!
                              :url "/about"}
                             {:title "Second time's the charm"    ;fix autograbber!
                              :text "Well what then now" ;fix autograbber!
                              :url "/blog"}]

             :gallery [{:src "img/joen-mixer.jpg" :alt "My actual first web project"}
                       {:src "img/live-session-small.jpg" :alt "Ableton Live"}
                       {:src "img/ssiri-balcony-small.jpg" :alt "Some nice people"}
                       {:src "img/video-editing-small.jpg" :alt "Television"}
                       {:src "img/afterglow-new-web-old-small.jpg" :alt "My actual first web project"}]

             :blog    {:heading {:title "joe tol thought"
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}}
                       :posts [{:id 1 :title "And so it all begins"     :text "[i forget] how does *markdown* work??"
                                :comments []}
                               {:id 2 :title "My journey into wankery"  :text "jk i never _knew_"
                                :comments [{:id 1 :user "Majson" :title "Wow va fint" :text "Snud snud"}
                                           {:id 2 :user "Adson" :title "oj va fint" :text "Snud snud"}]}
                               {:id 3 :title "A new beginning: to blog" :text "Will I manage to make more than 2-3 posts this time?
                                                                             Sometimes I surprise myself, but mostly not. Verdict, _njäe_."
                                :comments []}
                               {:id 4 :title "And something also goes here - maybe a really long one that will have to break lines and bread and love and life"
                                :text "But then text is short."
                                :comments []}]}
             :docs    {:heading {:title "Documentation"
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}}}

             :footer [{:id "left"
                       :title "joen.tolgraven@gmail.com"
                       :text ["© 2019-2020"]
                       :img {:src "img/cljs.png" :alt "cljs logo"}}
                      {:id "middle"
                       :title ""
                       :text [""]}
                      {:id "right"
                     ; :title "More ways to get in touch"
                       :links [{:name "Github" :href "https://github.com/tolgraven" :icon "github"}
                               {:name "Twitter" :href "https://twitter.com/joen.tolgraven" :icon "twitter"}
                               {:name "Facebook" :href "https://facebook.com/tolgraven" :icon "facebook"}
                               {:name "Soundcloud" :href "https://soundcloud.com/tolgraven" :icon "soundcloud"}
                             ; {:name "linkedin once i grow up and have one lol"}
                               ]}]}

   :options {:auto-save-vars true
             :transition {:time 200 :style :slide} ; etc
             :hud {:timeout 30 :level :info}}})
; DONT FORGET STUPID #_ fucks you for some reason??

(rf/reg-event-db :init-db [rf/debug]
  (fn [db _]
    data))
