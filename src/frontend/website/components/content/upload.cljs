(ns website.components.content.upload
  "An upload row renders measured progress and processing as distinct states."
  (:require [website.component.registry]
            [website.components.content.upload-schema :as schema]
            [website.react :as r]
            [website.macros :refer-macros [defc]]))

(defc <upload-row>
  "Display a file transfer with native accessible progress, success/error surface and retry/remove actions. Processing has no invented percentage."
  {:features [[:exit {:class "upload-row--exit"}]]
   :spec-schema [:map [:name :string]
                      [:status [:enum :queued :uploading :processing :ready :failed]]
                      [:?progress schema/progress]
                      [:?error :string] [:?retry! fn?] [:?remove! fn?]]}
  [{:keys [name status progress error retry! remove!]}]
  (let [transferring? (= status :uploading)
        measured? (and transferring? progress)
        label (case status
                :queued "Waiting to upload"
                :uploading "Uploading"
                :processing "Preparing image"
                :ready "Uploaded"
                :failed (or error "Upload failed"))]
    [:li.upload-row {:data-status (cljs.core/name status)}
     [:span.upload-row__icon {:aria-hidden true}
      (case status :ready "✓" :failed "!" :processing "⋯" "↑")]
     [:div.upload-row__body
      [:strong.upload-row__name name]
      [:span.upload-row__status {:role (if (= status :failed) "alert" "status")} label]
      (when (#{:uploading :processing} status)
        [:progress.upload-row__progress
         (cond-> {:aria-label (str label ": " name)}
           measured? (assoc :value (:loaded progress) :max (:total progress)))])
      (when measured?
        [:small (str (js/Math.floor (* 100 (/ (:loaded progress) (:total progress)))) "%")])]
     [:div.upload-row__actions
      (when (and (= status :failed) retry!) [:button {:type "button", :on-click retry!} "Retry"])
      (when (and (not= status :ready) remove!) [:button {:type "button", :on-click remove!} "Remove"])]]))

(defc <upload-chip>
  "A typography-sized inline transfer widget; presence owns its final exit."
  {:features [[:exit {:class "upload-chip--exit"}]]}
  [{:keys [name status progress error retry! remove! exited!]}]
  (let [callback (r/use-ref exited!)
        label (case status :queued "Waiting" :uploading "Uploading"
                          :processing "Preparing" :ready "Uploaded" :failed (or error "Failed"))]
    (r/use-effect (fn [] (set! (.-current callback) exited!)) [exited!])
    (r/use-effect (fn [] (fn [] (when-let [f (.-current callback)] (f)))) [])
    [:span.upload-chip {:content-editable false, :data-status (cljs.core/name status), :title (str name ": " label)}
     [:span.upload-chip__icon {:aria-hidden true} (case status :ready "✓" :failed "!" "↑")]
     [:span.upload-chip__name name]
     [:span.upload-chip__status {:role (if (= status :failed) "alert" "status")} label]
     (when (#{:uploading :processing} status)
       [:progress.upload-chip__progress
        (cond-> {:aria-label (str label ": " name)}
          (and (= :uploading status) progress) (assoc :value (:loaded progress), :max (:total progress)))])
     (when (= :failed status)
       [:button {:type "button", :on-click retry!, :aria-label (str "Retry " name)} "↻"])
     (when (not= :ready status)
       [:button {:type "button", :on-click remove!, :aria-label (str "Remove " name)} "×"])]))

(defc <upload-slot>
  {:features [:presence]}
  [{:keys [id preview-ready?] :as item}]
  [:span.upload-slot
   (when-not preview-ready?
     ^{:key id} [<upload-chip> (assoc item :exit {:class "upload-chip--exit"})])])
