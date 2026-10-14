(ns website.supabase.shape
   #?(:cljs (:require-macros [website.build.site :refer [when-enabled]]))
  (:require #?(:clj [website.build.site :refer [when-enabled]])
   [website.store.contract :as contract]))

(def collection-doc-map contract/collection-doc-map)
(when-enabled :blog :comments (def flatten-comment-tree contract/flatten-comment-tree))
(when-enabled :blog :comments (def build-comment-tree contract/build-comment-tree))
(def firebase-export->contract contract/firebase-export->contract)
(def firebase-export->seed contract/firebase-export->seed)
(def contract->seed contract/contract->seed)
(def seed->contract contract/seed->contract)
