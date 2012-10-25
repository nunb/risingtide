(ns risingtide.jobs
  (:use risingtide.core)
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [risingtide
             [interests :as interests]
             [feed :as feed]
             [resque :as resque]
             [stories :as stories]
             [key :as key]
             [digest :as digest]
             [persist :as persisted]]))

(defn- add-interest-and-backfill!
  [redii type user-id object-id]
  (interests/add! redii user-id type object-id)
  ;; update and write feeds with last 24 hours of stories about this
  ;; object
  (doseq [feed-type ["c" "n"]]
    (let [feed-key (key/user-feed user-id feed-type)]
      (doseq [story
              (feed/user-feed-stories
               (persisted/stories (:stories redii) (key/format-key feed-type (first-char type) object-id)
                                  (- (now) (* 24 60 60)) (now)))]
        (digest/add-story-to-feed-cache redii feed-key story))
      (digest/write-feeds! redii [(key/user-feed user-id feed-type)]))))

(defn add-interest!
  [redii type [user-id object-id]]
  (bench (str "add interest in "type" "object-id" to "user-id)
         (add-interest-and-backfill! redii type user-id object-id)))

(defn add-interests!
  [redii type [user-ids object-id]]
  (bench (str "IGNORING add interest in "type" "object-id" to "(count user-ids)" users ")
         #_(doall
            (pmap #(interests/add! redii % type object-id) user-ids))))

(defn remove-interest!
  [redii type [user-id object-id]]
  (bench (str "remove interest in "type" "object-id" to "user-id)
         (interests/remove! redii user-id type object-id)))

(defn batch-remove-user-interests!
  [redii type [user-id object-ids]]
  (bench (str "removed interests in "(count object-ids)" "type"s from user "user-id)
         (doseq [object-id object-ids]
           (interests/remove! redii user-id type object-id))))

(defn add-story-to-interested-feeds!
  [redii story]
  (doall (pmap-in-batches #(digest/add-story-to-feed-cache redii % story)
                          (stories/interested-feeds redii story))))

(defn- add-card-story!
  [redii story]
  (bench (str "add card story "story)
         (when (feed/for-user-feed? story) (add-story-to-interested-feeds! redii story))
         (when (feed/for-everything-feed? story)
           (digest/add-story-to-feed-cache redii (key/everything-feed) story))))

(defn add-story!
  [redii story]
  (let [score (now)
        scored-story (assoc story :score score)]
    (case (stories/feed-type story)
      :card (do
              (stories/add! redii story score)
              (add-card-story! redii scored-story))
      :network nil)))

(defn build-feeds!
  [redii [user-id]]
  (bench (str "building feeds for user "user-id)
         (digest/build-for-user! redii user-id)))

(defn- process-story-job!
  [redii json-message]
  (let [msg (json/read-json json-message)
        args (:args msg)]
    (case (:class msg)
      "Stories::AddInterestInListing" (add-interest! redii :listing args)
      "Stories::AddBatchInterestsInListing" (add-interests! redii :listing args)
      "Stories::AddInterestInActor" (add-interest! redii :actor args)
      "Stories::AddInterestInTag" (add-interest! redii :tag args)
      "Stories::RemoveInterestInListing" (remove-interest! redii :listing args)
      "Stories::BatchRemoveInterestInListings" (batch-remove-user-interests! redii :listing args)
      "Stories::RemoveInterestInActor" (remove-interest! redii :actor args)
      "Stories::RemoveInterestInTag" (remove-interest! redii :tag args)
      "Stories::Create" (add-story! redii (reduce merge args))
      "Stories::BuildFeed" (build-feeds! redii args))))

(defn process-story-jobs-from-queue!
  [run? redii queue-keys]
  ;; doseq is not lazy, and does not retain the head of the seq: perfect!
  (doseq [json-message (take-while identity (resque/jobs run? (:resque redii) queue-keys))]
    (try
      (process-story-job! redii json-message)
      (catch Exception e
        (log/error "failed to process job:" json-message "with" e)
        (safe-print-stack-trace e)))))

