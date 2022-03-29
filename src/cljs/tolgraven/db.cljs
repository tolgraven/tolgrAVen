(ns tolgraven.db
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [cljs.reader]))

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


(def data ; default db. Needs to be cleaned out of content already haha.
  {:state {:menu false
           :is-loading {}
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
             :intro {:title "Building experiences"
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
                          ["Design"   "joint"      ["How bout that logo?" "Or this site"]]
                          ["Software" "code"       ["Open source" "Bespoke projects" "Rapid prototyping" "From (cl)JS to C++" "Current focus"]]
                          ["Web"      "laptop"     ["Want a site like this?" "Small or large projects" "my stack runs fulleth"]]]
                         :bg {:src "img/vim-code-small.jpg" :alt "neovim editor"}
                         :caption "neovim"}

             :moneyshot {:title "YOU"
                         :caption "Happy people enjoying my hospitality once upon a time. You'll have to pay tho, I'm broke now"
                         :bg {:src "img/crowd-lbp.JPG"}}

             :story {:heading {:title "Breaking things down"
                               :bg {:src "img/wide-spot-ctrl-small.jpg"}}
                     :title "Not a real company... yet"
                     :text "After spending my working life in sports television, and lacking the passion of my colleagues within that subject - despite interesting and varied work (everything from PA, graphics, video editing, 'When IT says it can't be done' tech wizard) I started a transition towards programming a number of years ago.
                            Currently studying computer science, and having picked up some web design chops the past few years to supplement my existing graphics experience, I built this website from scratch as a learning-cum-demo environment.  Then came the point where it started reflecting something real.
                            With a simple CMS, plus blog/comments system with user accounts, it's pretty full-featured and a good representation of my abilities. I think the Clojure and ClojureScript ecosystem seems good in this regard. It's daunting, but the lack of rails, as it were, might force a deeper understanding of each aspect. And it's close enough to the JS ecosystem everyone is in that skills will surely translate.
                            Also, Hiccup is awesome.

                            Well let's see what else. I make music, have dabbled in being a rave promoter, soundboy, am a big enough ally to have hosted gay parties in Uganda, and all-around upstanding chap. I reckon.
                            Before I run my own web design/custom music tech/yada yada real company I still try to do those things, on a hobbyist basis. Or working for the man?
                            Currently most of the front-end is finished (well, alpha finished) while the back-end is, shall we say... not.
                            Persistance of blog posts, comments, votes and users has been achieved by way of Firebase. Still need such back-end rarities as content not defined in app-db, and more.
                            Of course I actually intend to write stuff - about the process, life in general, I'm not sure yet. All in due time.
                            
                            Leave a comment! Let me build your website! Give me a job!"

                     :images [["headshot" {:src "img/Leg-tasteful-small.jpg" :alt "Joen"}
                               "Yours truly. Smiling!"]
                              ["multimeter" {:src "img/multimeter-driver-small.jpg" :alt "Broken driver"}
                               "Something smells fishy here. Sorry I mean burnt"
                               "right"]
                              ["cljs" {:src "img/cljs.png" :alt "cljs"}
                               "clojurescript in the house"] ]}

             :strava {:story "I used to be in really good shape, before stomach issues and multiple surgeries.
                              Cycling became my way back to fitness, and I've gone pretty much all in. Here you can follow my progress, or lack of it."}

             :interlude [{:title "What does that mean?"
                          :caption "Stage light visualizer for the browser with custom shaders"
                          :bg [:video.media.media-as-bg
                               {:poster "media/fog-3d-small.png" :src "media/fog-3d-small.mp4" :playsInline true :autoPlay true :muted true } ]}
                         {:title "For who?"
                          :caption "Nihiloxica video shoot, Kampala"
                          :bg [:video.media.media-as-bg
                               {:poster "media/nihil-shoot2.png" :src "media/nihil-shoot2.mp4" :playsInline true :autoPlay true :muted true } ]}
                         {:title "Portfolio forthcoming, have some components"
                          :caption "Be ready"
                          :bg [:img.media.media-as-bg {:src "img/collage-strips.jpg"}]}
                         {:title "CV"
                          :caption "My resume. such as it is."
                          :bg [:img.media.media-as-bg.anim-gradient-bg {:src "img/wide-spot-ctrl-small.jpg"}]
                          :cv {:intro "Multi talented developer with a background in live TV production, who throughout the years has also kept occupied with music, events, moving the world over etc. Hitherto autodidact currently getting a Comp Sci degree at an accelerated pace.
                                       Main focus is web development, primarily front-end.
                                       Constructing my website https://tolgraven.se from scratch as a showcase of my skills."
                               :education
                               [["2020–  "   "Bachelor programme, computer science, Stockholm University"]
                                ["2019–2020" "Full stack web development, Chas Academy"]
                                ["2012–2014" "Musicology, Stockholm University (60 hp)"]
                                ["2007–2009" "Industriell Ekonomi, Royal Institute of Technology"]
                                ["2004–2007" "Natural Science program, mathematics and computer science, Kärrtorps gymnasium" ]]
                               }}]

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
                                 :target :blog
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}
                                 :tint "bg-5"}}
             :docs    {:heading {:title "Documentation"
                                 :bg {:src "img/wide-spot-ctrl-small.jpg"}}}
             :common  {:banner-heading {:bg {:src "img/wide-spot-ctrl-small.jpg"}}
                       :user-avatar-fallback "img/tolgrav-square.png"}

             :footer [{:id "left"
                       :title "joen.tolgraven@gmail.com"
                       :text ["© 2020-2022"]
                       :img {:src "img/cljs.png" :alt "cljs logo"}}
                      {:id "right"
                       :links [{:name "Github" :href "https://github.com/tolgraven" :icon "github"}
                               {:name "LinkedIn" :href "https://www.linkedin.com/in/joen-tolgraven-5205701b9" :icon "linkedin"}
                               {:name "Instagram" :href "https://www.instagram.com/tolgraven" :icon "instagram"}
                               {:name "Twitter" :href "https://twitter.com/joen.tolgraven" :icon "twitter"}
                               {:name "Facebook" :href "https://facebook.com/tolgraven" :icon "facebook"}
                               {:name "Soundcloud" :href "https://soundcloud.com/tolgraven" :icon "soundcloud"}
                               ]}]}

   :options {:auto-save-vars true
             :transition {:time 200 :style :slide} ; etc
             :blog {:posts-per-page 3}
             :firebase {:project :main
                        :settings {}} ; {:timestampsInSnapshots true} ; for old version
             :hud {:timeout 30 :level :info}}})


(rf/reg-event-db :init-db
  (fn [db _]
    data))
