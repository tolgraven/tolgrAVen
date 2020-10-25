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
           :experiments :parallax
           :debug {:layers false
                   :divs false}
           ; :transition :out ;later when proper boot sequence, trigger in on load complete
           }
   :routes {:home "/"
            :about "/about"
            :docs "docs"
            :blog "/blog"
            :log "log"}
   :content {:header {:text ["tolgrAVen" ["audio" "visual"]]
                      :text-personal ["tolgraven" ["" "joen"]]
                      :menu {:work  [["Services"  "#link-services" :services] ; should have two sections either by collapse
                                     ["Story"     "#about"        :about]         ; or just opp sides with the /
                                     ["Tools"     "#tools"        :tools]
                                     ["Portfolio" "#portfolio"    :portfolio]]
                             :personal [["Blog"      "#/blog"     :blog]
                                        ["Docs"      "#/docs"     :docs]
                                        ["Test"      "#/test"     :docs]
                                        ["Log"       "#/log"      :log]]} }
             :intro {:title "Building experience"
                     :text "Is what it all comes down to.
                            So many roads, yet I'm determined to track most of them halfway down.
                            There's only one way to find out."
                     :buttons  [["Join me"            "#should-open-user-panel"]
                                ["Read my thoughts"   "#/blog"]]
                     :bg [{:src "img/foggy-shit-small.jpg" :alt "Purple enthusiast"}
                          {:src "img/live-session-small.jpg" :alt "Ableton Live"}
                          {:src "img/afterglow-new-web-old-small.jpg" :alt "My actual first web project"}]
                     :logo-bg "img/tolgrav.png"} ; no :src cause goes in :style background-image...

             :services  {:categories
                         [["Audio"    "bullhorn"   ["Sound engineering" "Broadcasting" "Recording" "Music production"]]
                          ["Video"    "film"       ["Editing" "Production" "Shoot inna pinch"]]
                          ["Stage"    "lightbulb"  ["Event production" "Concert" "Club" "Automation" "Retrofitting"]]
                          ["Design"   "joint"      ["I mean not really" "But sorta dig the logo" "Not like I'm qualified" "For any of this"]]
                          ["Software" "code"       ["Open source (evtly)" "Bespoke projects" "Rapid prototyping" "From (cl)JS to C++" "Current focus"]]
                          ["Web"      "laptop"     ["This is my first website!" "I'll make more once" "my stack runs fulleth"]]]
                         :bg {:src "img/vim-code-small.jpg" :alt "neovim editor"}
                         :caption "neovim"}

             :moneyshot {:title "YOU"
                         :caption "Happy people enjoying my hospitality once upon a time. You'll have to pay tho, I'm broke now"
                         :bg {:src "img/crowd-lbp.JPG"}}

             :story {:heading {:title "Breaking things down"
                               :bg {:src "img/wide-spot-ctrl-small.jpg"}}
                     :title "Not a real company... yet"
                     :text "After spending my working life in sports television, and lacking the passion of my colleagues - despite interesting and varied work (everything from PA, graphics, video editing, 'When IT says it can't be done' tech wizard) I started a transition towards programming a number of years ago.
                            Currently studying computer science, and having picked up some web design chops last year, I'm building this website from scratch as a learning environment.  The hope of course being it'll one day reflect something real.
                           My plan is to build a simple CMS, plus blog/comments system with user accounts. I think the Clojure and ClojureScript ecosystem seems good in this regard. It's daunting, but the lack of rails, as it were, might force a deeper understanding of each aspect. And it's close enough to the JS ecosystem everyone is in that skills will surely translate.
                            Also, Hiccup is awesome.

                            Well let's see what else. I make music, have dabbled in being a rave promoter, soundboy, am a big enough ally to have hosted gay parties in Uganda, and all-around upstanding chap. I reckon.
                            Before I run my own web design/custom music tech/yada yada real company I still try to do those things, on a hobbyist basis.
                            Currently most of the front-end is finished (well, pre-alpha finished) while the back-end is, shall we say... not. Everything will be hooked up in due time though. And optimized - this website hardly currently plays nice with more limited hardware. But have a look around, you can make a user account and post away at the blog. safe in the knowledge it won't leave your browser.  "

                     :images [["headshot" {:src "img/Leg-tasteful-small.jpg" :alt "Joen"}
                               "Yours truly. Smiling!"]
                              ["multimeter" {:src "img/multimeter-driver-small.jpg" :alt "Broken driver"}
                               "Something smells fishy here. Sorry I mean burnt"
                               "right"]
                              ["cljs" {:src "img/cljs.png" :alt "cljs"}
                               "clojurescript in the house"] ]}

             :interlude [{:title "What does that mean?"
                          :caption "Stage light blabla"
                          :bg [:video.media.media-as-bg {:src "media/fog-3d-small.mp4"  :playsInline true :autoPlay true }]}
                         {:title "For who?"
                          :caption "Nihiloxica video shoot, Kampala"
                          :bg [:video.media.media-as-bg {:src "media/nihil-shoot2.mp4"  :playsInline true :autoPlay true :loop true}]}
                         {:title "Got no portfolio"
                          :caption "Failed"
                          :bg [:img.media.media-as-bg.fade-3 {:src "img/collage-strips.jpg"}]}
                         {:title "Placeholder"
                          :caption "Fix this shite"
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
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}
                                 :tint "bg-5"}}
             :docs    {:heading {:title "Documentation"
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}}}
             :common  {:banner-heading {:bg {:src "img/wide-spot-ctrl-small.jpg"}}}

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
