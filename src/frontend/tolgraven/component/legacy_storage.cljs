(ns tolgraven.component.legacy-storage
  "Compatibility boundary for the original :state store. Keep its Transit key
   and payload until all legacy callers have migrated to component snapshots."
  (:require [cognitect.transit :as transit]
            [tolgraven.component.storage :as storage]
            [tolgraven.service-status :as status]))

(defn encode [value] (transit/write (transit/writer :json) value))
(defn decode [text] (transit/read (transit/reader :json) text))
(def disk-key (encode :state))
(defonce *state (atom nil))
(defonce *loaded? (atom false))
(defonce *pending (atom nil))

(defn- warn! []
  (status/fail! :component-storage "Local data could not be saved"
                "Browser storage is unavailable or full. Your current page still works." nil))

(defn drain! []
  (when @*pending
    (js/clearTimeout @*pending)
    (reset! *pending nil)
    (try
      (storage/write-disk! disk-key (encode @*state))
      (status/recover! :component-storage)
      (catch :default _ (warn!)))))

(defn read! []
  (when-not @*loaded?
    ;; Blocked storage and damaged old data must not prevent mounting the page.
    (reset! *state (try
                     (when-let [text (storage/read-disk! disk-key)] (decode text))
                     (catch :default _ nil)))
    (reset! *loaded? true))
  @*state)

(defn write! [value]
  (read!)
  (when-not (= value @*state)
    (reset! *state value)
    (when @*pending (js/clearTimeout @*pending))
    (reset! *pending (js/setTimeout drain! 10))))

(defn storage-event! [event]
  (try
    (when (and (exists? js/window)
               (identical? (.-storageArea event) (.-localStorage js/window))
               (or (nil? (.-key event)) (= disk-key (.-key event))))
      (let [text (.-newValue event)]
        ;; Decode first: malformed events must not cancel a valid local write.
        (let [value (when (seq text) (decode text))]
          (when @*pending (js/clearTimeout @*pending))
          (reset! *pending nil)
          (reset! *state value)
          (reset! *loaded? true))))
    ;; Accessing localStorage can itself throw when browser storage is blocked.
    (catch :default _ nil)))
