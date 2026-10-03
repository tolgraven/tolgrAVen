(ns tolgraven.supabase.shape
  (:require
   [tolgraven.store.contract :as contract]))

(def collection-doc-map contract/collection-doc-map)
(def flatten-comment-tree contract/flatten-comment-tree)
(def build-comment-tree contract/build-comment-tree)
(def firebase-export->contract contract/firebase-export->contract)
(def firebase-export->seed contract/firebase-export->seed)
(def contract->seed contract/contract->seed)
(def seed->contract contract/seed->contract)
