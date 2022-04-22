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
                      :menu {:work  [["Services"  "services"  :services] ; should have two sections either by collapse
                                     ["Story"     "about"        :about]         ; or just opp sides with the /
                                     ["Hire me"   "hire"         :hire]
                                     ["CV"        "cv"            :cv]]
                             :personal [["Blog"      "/blog"     :blog]
                                        ["Docs"      "/docs"     :docs]
                                        ["Test"      "/test"     :test]
                                        ["Log"       "/log"      :log]]} }
             :intro {:title "Building experiences"
                     :text "Is what it all comes down to.
                            So many roads, yet I'm determined to track most of them halfway down.
                            There's only one way to find out."
                     :buttons  [["Check my CV"        "/cv"]
                                ["Read my thoughts"   "/blog"]]
                     :bg [{:src "img/foggy-shit-small.jpg" :alt "Purple enthusiast"}
                          {:src "img/live-session-small.jpg" :alt "Ableton Live"}
                          {:src "img/afterglow-new-web-old-small.jpg" :alt "My actual first web project"}]
                     :logo-bg "img/tolgrav.png"} ; no :src cause goes in :style background-image...

             :services  {:categories
                         [["Audio"    "bullhorn"   ["Sound engineering" "Broadcasting" "Recording" "Music production"]]
                          ["Video"    "film"       ["Editing" "Production" "Shooting"]]
                          ["Stage"    "lightbulb"  ["Event production" "Concert" "Club" "Automation" "Retrofitting"]]
                          ["Design"   "joint"      ["Websites" "Logos" "Brand colors"]]
                          ["Software" "code"       ["Open source" "Bespoke projects" "Rapid prototyping" "From (cl)JS to C++"]]
                          ["Web"      "laptop"     ["Want a site like this?" "Small or large projects" "my stack runs fulleth"]]]
                         :bg {:src "img/vim-code-small.jpg" :alt "neovim editor"}
                         :caption "neovim"}

             :moneyshot {:title "YOU"
                         :caption "Happy people enjoying my hospitality once upon a time. You'll have to pay tho, I'm broke now"
                         :bg {:src "img/crowd-lbp.JPG"}}

             :story {:heading {:title "Breaking things down"
                               :bg {:src "img/wide-spot-ctrl-small.jpg"}}
                     :title "The ideal partner for your project"
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
                          :bg [:img.media.media-as-bg {:src "img/collage-strips.jpg"}]}]
             :cv        {:heading {:title "CV"
                                   :target :cv
                                   :bg {:src "img/wide-spot-ctrl-small.jpg"}
                                   :tint "fg-6"}
                         :title "CV"
                         :caption "My resume. such as it is."
                         :cv {:intro "Multi talented developer with a background in live TV production, who throughout the years has also kept occupied with music, events, moving the world over etc. Hitherto autodidact currently getting a Comp Sci degree at an accelerated pace.
                                      Main focus is functional programming and web development, primarily front-end."
                              :education
                              [{:from 2004, :to 2007, :what "Natural Science program, mathematics and computer science", :how ["3.96 (19.8) GPA"], :where "Kärrtorps gymnasium" :logo "img/logo/karrtorp-logo.png" :color "var(--purple)"}
                               {:from 2007, :to 2009, :what "Industrial Economics and Management", :how [], :where "Royal Institute of Technology" :logo "img/logo/KTH-logo.jpg" :color "var(--blue)"}
                               {:from 2012, :to 2014, :what "Musicology (60hp)", :how [], :where "Stockholm University" :logo "img/logo/su-logo.png" :color "var(--yellow)"}
                               {:from 2019, :to 2020, :what "Full stack web development", :how [], :where "Chas Academy" :logo "img/logo/chas-logo.png" :color "var(--green)"}
                               {:from 2020, :to 2023 , :what "Bachelor programme, Computer Science", :how ["remote"]  :where "Stockholm University" :logo "img/logo/su-logo.png" :color "var(--yellow)"}]
                              :work
                              [{:from 2001  :to 2005  :what "SvFF"  :position "Referee", :how ["From 5, 7 to 11 (adult) games"] :color "var(--green-3)"  :logo "img/logo/svff-logo.png" :level 0}
                               {:from 2007, :to 2010, :what "TV4 Sport", :position "PA & Live graphics operator" :how ["Team leader and responsible for group of 7-10 people in live graphics department.
", "Specified, ordered and followed up implementation, rollout and operations for projects, many of them TV graphics solutions, internally vis a vis project leads as well as externally with solutions providers like ChyronHego, Ericsson Broadcast Systems and buyers (for example Svenska Spel).
", "On-site responsibility for testing of systems, communication with providers and operators, working with them on solving issues (or often debugging them myself), reporting and follow-up. Included server administration, code-level debugging and fixes..
", "Throughout the years I also worked on things like the ice hockey World Championship, 
Fotbollskanalen Europa, Club Calcio and TV4Nyheterna in a variety of roles."], :where "Stockholm" :logo "img/logo/TV4Sport-logo.png" :color "var(--orange)" :level 1}
                               {:from 2007, :to 2018, :what "TV4", :position "Many different positions, expand for details", :how ["Mainly assistant editor and video editor but on project basis also editor, 
producer, reporter, PA, graphics technician, music administrator etc"] :where "Stockholm" :logo "img/logo/TV4-logo.svg" :color "var(--red-2)" :level 0}
                               {:from 2008  :to 2011  :what "Royal Institute of Technology" :position "Programming lab/teaching assistant" :how ["Python, C++, Java"] :logo "img/logo/KTH-logo.jpg" :color "var(--blue)" :level 2}
                               {:from 2010, :to 2018, :what "TV4 Sport", :position "Junior producer & live graphics team leader" :how ["Live graphics team leader", "Junior producer", ""], :where "Stockholm" :logo "img/logo/TV4Sport-logo.png" :color "var(--orange)" :level 1}
                               {:from 2012, :to 2016, :what "A number of clients", :position "Freelance" :how ["SvT", "Endemol", "Svenska Spel",  "Nyhetsbolaget" "C More" "Ericsson Broadcast Systems", "2AM", "Oh my!", "and more"], :where "Stockholm"  :color "#cfcbc4" :level 2}
                               {:from 2014, :to 2018, :what "TV4 Sport", :position "Developer" :how ["Developed custom creative tech solutions, generally as a response to IT department or tech suppliers saying a requested feature or workflow wasn’t possible on our tech stack.
“The missing one-man in-house development team”.", "Personally developed and operated systems during large TV events (such as the football World Cup) to eg. record 2-10+ different channels of international TV and radio, keep all streams syncronized to each other as well as our own feeds, and using this footage and audio live. A combination of network administration, scripting, programming, audio tech and video editing.
", ""], :where "Stockholm" :logo "img/logo/TV4Sport-logo.png" :color "var(--orange)" :level 3}
                               {:from 2013, :to 2022, :what "Club/party promoter", :position "Promoter" :how ["Rave promoter", "Venue operation"], :color "#cfcbc4" :level 4},
                               {:from 2018, :to 2019, :what "Systim Nostra", :position "Manager" :how ["Soundsystem operator", "Venue operation"], :where "Kampala" :color "#cfcbc4" :level 0},
                               {:from 2019, :to 2023,  :what "tolgrAVen audiovisual", :position "Owner-operator, Web developer" :how ["Owner-operator", "Web designer & developer"], :where "Stockholm" :logo "img/tolgrav-square.png" :color "#cfcbc4" :level 0}
                               {:from 2021, :to 2023,  :what "Crosstown", :how [], :position "Cyclist" :where "Stockholm" :logo "img/logo/crosstown-logo.png" :color "var(--orange-2)" :level 1}
                               {:from 2022, :to 2025, :what "Your company here", :position "Web developer, or something else" :how ["Web developer"], :where "Remote, hybrid or office" :logo "img/questionmark.png" :color "var(--aqua)" :level 2}]
                              :life
                              [{:from 1988  :to 1988  :what "Born"}
                               {:from 1994  :to 2001  :what "Football Player"}
                               {:from 2003  :to 2006  :what "Started producing music" :color "var(--purple-2)" :level 0}
                               {:from 2004  :to 2007  :what "First started coding, in C++ and OpenGL" :color "var(--aqua-3)" :level 1}
                               {:from 2009  :to 2011  :what "Lived in Australia" :color "var(--green-3)" :level 2}
                               {:from 2012  :to 2015  :what "Got serious about music production" :color "var(--purple-2)" :level 0}
                               {:from 2013  :to 2015  :what "Started coding again, with goal of making it a career" :color "var(--aqua-3)" :level 1}
                               {:from 2016  :to 2018  :what "Started doing functional programming" :color "var(--aqua-3)" :level 1}
                               {:from 2019  :to 2021  :what "Started focusing on web development" :color "var(--aqua-3)" :level 1}
                               {:from 2018  :to 2019  :what "Lived in Uganda" :color "var(--green-3)" :level 2}]
                              :skills {:software ["Full stack web development and design"
                                                  "Specialized in front-end work: JavaScript (React/Native), CSS"
                                                  "Functional programming, Clojure/ClojureScript (Reagent/Re-frame)"
                                                  "Python, Java, C++"
                                                  "3D and 2D graphics"
                                                  "Git, Docker, Heroku, Firebase, GraphQL, AWS etc"
                                                  "Bash, zsh, fish (20k+ loc), Powershell"
                                                  "Vim ninja"
                                                  "Server admin and some DevOps"]
                                       :digital ["Adobe Premiere, Final Cut Pro X",
                                                 "Adobe Photoshop, After Effects, Illustrator"
                                                 "Ableton Live, Logic Pro, iZotope RX, Adobe Audition"
                                                 "Max/MSP, TouchDesigner, Resolume"
                                                 "Microsoft Excel / Google Sheets - advanced scripting"
                                                 "OS - macOS/Darwin Unix, Linux, Windows including Windows Server"]
                                       :general ["Project management"
                                                 "Working with internal and external stakeholders and ensuring projects stays on track"
                                                 "Interop between technical and artistic or executive"
                                                 "Research"
                                                 "Documenting best practices to ensure stability and ease onboarding"]
                                       :language ["Swedish - Native"
                                                  "English - Fluent, native level"
                                                  "Spanish - Limited, but get by in Spanish speaking countries"]}}}
             :soundcloud {:url "https://soundcloud.com/"
                          :artist "tolgraven"
                          :tunes ["pop-music-for-cool-people-sketch-1-session-1"
                                  "stateless-nearing-completion-messy-mix"
                                  "a-taste-of-what-i-will-sound-like-live"]}

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
                       :text [(str "© 2020-" (.getFullYear (js/Date.)))]
                       :logo {:src "img/tolgrav-square.png" :alt "tolgrAVen logo"}}
                      {:id "right"
                       :links [{:name "Github" :href "https://github.com/tolgraven" :icon "github"}
                               {:name "LinkedIn" :href "https://linkedin.com/in/tolgraven" :icon "linkedin"}
                               {:name "Instagram" :href "https://instagram.com/tolgraven" :icon "instagram"}
                               {:name "Twitter" :href "https://twitter.com/joentolgraven" :icon "twitter"}
                               {:name "Facebook" :href "https://facebook.com/tolgraven" :icon "facebook"}
                               {:name "Soundcloud" :href "https://soundcloud.com/tolgraven" :icon "soundcloud"}]}]
             :post-footer [{:id "left"
                            :title "Inquiries"
                            :text ["Send me an email and let's discuss your idea!"
                                   "Open to projects, long-term freelance work,"
                                   "full-time employment."
                                   "•"
                                   "On your page this will probably be"
                                   "some massive sitemap."]}
                           {:id "right"
                            :title "Links"
                            :links [{:name "Github" :href "https://github.com/tolgraven" :info "tolgraven"}
                                    {:name "LinkedIn" :href "https://linkedin.com/in/tolgraven" :info "tolgraven"}
                                    {:name "Instagram" :href "https://instagram.com/tolgraven" :info "tolgraven"}
                                    {:name "Twitter" :href "https://twitter.com/joentolgraven" :info "joentolgraven"}
                                    {:name "Facebook" :href "https://facebook.com/tolgraven" :info "tolgraven"}
                                    {:name "Soundcloud" :href "https://soundcloud.com/tolgraven" :info "tolgraven"}]}
                           {:id "third"
                            :title "The stack"
                            :text ["While we command many a paradigm, our stack of choice is a most powerful weapon."
                                   "This is how the site is built:"]
                            :img [{:src "img/logo/icon/clojure-logo-icon.png" :alt "clojure logo"}
                                  {:src "img/logo/icon/react-logo-icon.png" :alt "react logo"}
                                  {:src "img/logo/icon/sass-logo-icon.png" :alt "sass logo"}
                                  {:src "img/logo/icon/re-frame-logo.png" :alt "re-frame logo"}]}]}

   :options {:auto-save-vars true
             :transition {:time 200 :style :slide} ; etc
             :blog {:posts-per-page 3}
             :firebase {:project :main
                        :settings {}} ; {:timestampsInSnapshots true} ; for old version
             :hud {:timeout 30 :level :info}}})


(rf/reg-event-db :init-db
  (fn [db _]
    data))
