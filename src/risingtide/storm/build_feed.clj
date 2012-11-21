(ns risingtide.storm.build-feed
  (:require [risingtide.model.story :refer [->ListingLikedStory]]
            [risingtide.storm
             [action-spout :refer [resque-spout]]
             [recent-actions-bolt :refer [recent-actions-bolt]]
             [story-bolts :refer [create-story-bolt]]
             [active-user-bolt :refer [active-user-bolt]]
             [interests-bolts :refer [like-interest-scorer follow-interest-scorer
                                      seller-follow-interest-scorer interest-reducer]]
             [feed-build-bolt :refer [drpc-feed-build-bolt]]
             [feed-bolts :refer [serialize-feed]]
             [drpc :as drpc]]
            [backtype.storm [clojure :refer [defbolt bolt emit-bolt! ack! topology]] [config :refer [TOPOLOGY-DEBUG]]])
  (:import [backtype.storm LocalCluster LocalDRPC]
           [backtype.storm.coordination BatchBoltExecutor]
           [risingtide FeedBuilder]))

(defn spouts [drpc]
  (drpc/topology-spouts drpc "build-feed" "drpc-feed-build-requests"))

(defn bolts []
  (drpc/topology-bolts
   "drpc-feed-build-requests"
   ["drpc-feed-builder" drpc-feed-build-bolt :p 24]

   {"drpc-serialize-feed" [{"drpc-feed-builder" :shuffle}
                           serialize-feed :p 24]}

   {}
   ["drpc-serialize-feed" "feed"]))

(defn feed-build-topology [drpc]
  (topology (spouts drpc) (bolts)))

(comment
  (def c (LocalCluster.))
  (def d (LocalDRPC.))
  (.submitTopology c "build-feed" {TOPOLOGY-DEBUG true} (feed-build-topology d))
  (.execute d "build-feed" "1")
  (.shutdown c)

  ;; lein run -m risingtide.storm.core/run-local!
  ;; brooklyn:
  ;; User.inject_listing_story(:listing_liked, 2, Listing.find(23))
  )