(ns website.components.content.upload
  "An upload row renders measured progress and processing as distinct states."
  (:require [website.component.registry]
            [website.components.content.upload-schema :as schema]
            [website.macros :refer-macros [defc]]))

(defc <upload-row>
  "Display a file transfer with native accessible progress, success/error surface and retry/remove actions. Processing has no invented percentage."
  {:features [:exit]
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
