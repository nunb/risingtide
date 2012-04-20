(ns risingtide.jobs
  (:use risingtide.core)
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [accession.core :as redis]
            [risingtide
             [interests :as interests]
             [feed :as feed]
             [resque :as resque]
             [stories :as stories]
             [key :as key]
             [digest :as digest]]))

(defn- add-interest-and-backfill!
  [redii type user-id object-id]
  (interests/add! redii user-id type object-id)
  ;; update and write feeds with last 24 hours of stories about this object
  (doall
   (for [feed-type ["c" "n"]]
     (let [feed-key (key/user-feed user-id feed-type)]
       (doall
        (for [story (feed/user-feed-stories
                     (digest/load-stories (:stories redii) (key/format-key feed-type (first-char type) object-id)
                       (- (now) (* 24 60 60)) (now)))]
          (digest/add-story-to-feed-cache redii feed-key story)))
       (digest/write-feeds! redii [(key/user-feed user-id feed-type)])))))

(defn add-interest!
  [redii type [user-id object-id]]
  (bench (str "add interest in " type object-id " to " user-id)
         (add-interest-and-backfill! redii type user-id object-id)))

(defn add-interests!
  [redii type [user-ids object-id]]
  (bench (str "add interest in " type object-id " to " (count user-ids) " users ")
         (doseq [user-id user-ids]
           (interests/add! redii user-id type object-id))))

(defn remove-interest!
  [redii type [user-id object-id]]
  (bench (str "remove interest in " type object-id " to " user-id)
         (interests/remove! redii user-id type object-id)))

(defn add-story-to-interested-feeds!
  [redii story]
  (doall (pmap-in-batches #(digest/add-story-to-feed-cache redii % story)
                          (stories/interested-feeds redii story))))

(defn- add-card-story!
  [redii story]
  (bench (str "add card story " story)
         (when (feed/for-user-feed? story) (add-story-to-interested-feeds! redii story))
         (when (feed/for-everything-feed? story)
           (digest/add-story-to-feed-cache redii (key/everything-feed) story))))

(defn- add-network-story!
  [redii story]
  (bench (str "add network story " story) (add-story-to-interested-feeds! redii story)))

(defn add-story!
  [redii story]
  (let [score (now)
        scored-story (assoc story :score score)]
    (stories/add! redii story score)
    (case (stories/feed-type story)
      :card (add-card-story! redii scored-story)
      :network (add-network-story! redii scored-story))))

(defn build-feeds!
  [redii [user-id]]
  (bench (str "building feeds for user " user-id)
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

