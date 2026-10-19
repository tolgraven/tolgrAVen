(ns website.modules.github.subs
  (:require [clojure.string :as string]
            [website.modules.github.model :as model]
            [website.react :as r]))

(r/reg-sub :github/configuration-result
 (fn [db _]
   (get-in db [:module :github :configuration])))

(r/reg-sub :github/configuration
 :<- [:github/configuration-result]
 (fn [result _]
   (:settings result)))

(r/reg-sub :github/get-from
 :<- [:github/configuration]
 (fn [{:keys [user repo]}]
   [user repo]))

(r/reg-sub :github/website-url
 :<- [:github/get-from]
 (fn [[user repo] _]
   (when (and (seq user) (seq repo))
     (str "https://github.com/" user "/" repo))))

(r/reg-sub :github/commits
 :<- [:content [:github]]
 :<- [:form-field [:github :search]]
 (fn [[github query] _]
   (let [sha (string/lower-case (string/trim (or query "")))]
     (if (re-matches #"[a-f0-9]{7,40}" sha)
       (let [cached (or (get-in github [:commit sha])
                        (some #(when (model/matches? % sha) %) (vals (:commit github))))]
         (filterv #(model/matches? % sha)
                  (cond-> (:commits github)
                    cached (model/merge-commits [(model/normalize-commit cached)]))))
       (:commits github)))))

(r/reg-sub :github/commit-count
 :<- [:content [:github :total]]
 :<- [:github/commits]
 (fn [[total commits] _]
   (if (some? total) (max total (count commits))
     (if (seq commits) (count commits) "Loading"))))

(r/reg-sub :github/archive-complete?
 :<- [:content [:github :complete?]]
 (fn [complete? _] (true? complete?)))

(r/reg-sub :github/more?
 :<- [:content [:github :next-cursor]]
 (fn [cursor _] (some? cursor)))

(r/reg-sub :github/commit
 :<- [:content [:github]]
 (fn [github [_ sha]]
   (get-in github [:commit sha])))

(r/reg-sub :github/filter-by
 :<- [:github/commits]
 :<- [:form-field [:github :search]]
 (fn [[commits search] [_ override]]
   (filterv #(model/matches? % (or override search)) commits)))

(r/reg-sub :github/route-target
  :<- [:common/route]
  (fn [match _] (model/route-target match)))

(r/reg-sub :github/commit-error
  :<- [:content [:github :commit-errors]]
  (fn [errors [_ sha]] (get errors sha)))

(r/reg-sub :github/commit-listed?
  :<- [:github/commits]
  (fn [commits [_ sha]]
    (boolean (and sha (some #(string/starts-with? (:sha %) sha) commits)))))


(r/reg-sub :github/commit-pending?
  :<- [:state [:github :commits-pending]]
  (fn [pending [_ sha]] (contains? pending sha)))
