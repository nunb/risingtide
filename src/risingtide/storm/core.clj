(ns risingtide.storm.core
  (:require [risingtide.config :as config]
            [risingtide.storm
             [action-spout :refer [resque-spout]]
             [story-bolts :refer [create-story-bolt save-story-bolt]]
             [action-bolts :refer [prepare-action-bolt save-action-bolt]]
             [active-user-bolt :refer [active-user-bolt]]
             [interests-bolts :refer [like-interest-scorer follow-interest-scorer
                                      tag-like-interest-scorer
                                      seller-follow-interest-scorer interest-reducer]]
             [feed-bolts :refer [add-to-feed add-to-curated-feed]]
             [build-feed :as feed-building]]
            [risingtide.storm.drpc.local-server :as local-drpc-server]
            [backtype.storm [clojure :refer [defbolt bolt emit-bolt! ack! topology spout-spec bolt-spec]] [config :refer :all]]
            [metrics.core :refer [report-to-console]])
  (:import [backtype.storm LocalCluster LocalDRPC]))

(defbolt drpc-acker ["id" "user-id" "feed"] [{id "id" user-id "user-id" feed "feed" :as tuple} collector]
  (emit-bolt! collector [id user-id feed])
  (ack! collector tuple))

(def standard-topology-config
  {TOPOLOGY-FALL-BACK-ON-JAVA-SERIALIZATION false
   TOPOLOGY-KRYO-REGISTER
   [{"risingtide.model.story.TagLikedStory" "risingtide.serializers.TagLikedStory"}
    {"risingtide.model.story.ListingLikedStory" "risingtide.serializers.ListingLikedStory"}
    {"risingtide.model.story.ListingCommentedStory" "risingtide.serializers.ListingCommentedStory"}
    {"risingtide.model.story.ListingActivatedStory" "risingtide.serializers.ListingActivatedStory"}
    {"risingtide.model.story.ListingSoldStory" "risingtide.serializers.ListingSoldStory"}
    {"risingtide.model.story.ListingSharedStory" "risingtide.serializers.ListingSharedStory"}
    {"risingtide.model.story.MultiActorMultiActionStory" "risingtide.serializers.MultiActorMultiActionStory"}
    {"risingtide.model.story.MultiActorStory" "risingtide.serializers.MultiActorStory"}
    {"risingtide.model.story.MultiActionStory" "risingtide.serializers.MultiActionStory"}
    {"risingtide.model.story.MultiListingStory" "risingtide.serializers.MultiListingStory"}]})

(defn feed-generation-topology
  ([] (feed-generation-topology nil))
  ([drpc]
     (topology
      (merge {"actions" (spout-spec resque-spout)} (feed-building/spouts drpc))

      (merge
       {"prepare-actions" (bolt-spec {"actions" :shuffle}
                                     prepare-action-bolt)

        "save-actions" (bolt-spec {"prepare-actions" :shuffle}
                                  save-action-bolt)

        "stories" (bolt-spec {"prepare-actions" :shuffle}
                             create-story-bolt)

        ;; everything feed
        "curated-feed" (bolt-spec {"stories" :global}
                                  add-to-curated-feed
                                  :p 1)

        ;; search and browse cache
        "search-and-browse" (bolt-spec {"stories" :global}
                                       save-story-bolt
                                       :p 1)

        ;; user feeds

        "active-users" (bolt-spec {"stories" :shuffle}
                                  active-user-bolt
                                  :p 3)

        "likes" (bolt-spec {"active-users" :shuffle}
                           like-interest-scorer
                           :p 6)
        "tag-likes" (bolt-spec {"active-users" :shuffle}
                               tag-like-interest-scorer
                               :p 6)
        "follows" (bolt-spec {"active-users" :shuffle}
                             follow-interest-scorer
                             :p 6)
        "seller-follows" (bolt-spec {"active-users" :shuffle}
                                    seller-follow-interest-scorer
                                    :p 6)

        "interest-reducer" (bolt-spec {"likes" ["user-ids-hash"]
                                       "tag-likes" ["user-ids-hash"]
                                       "follows" ["user-ids-hash"]
                                       "seller-follows" ["user-ids-hash"]}
                                      interest-reducer
                                      :p 6)

        "drpc-acker" (bolt-spec {["drpc-feed-builder" "story"] :shuffle}
                                drpc-acker
                                :p 3)

        "add-to-feed" (bolt-spec {"interest-reducer" ["user-id"]
                                  "drpc-acker" ["user-id"]
                                  }
                                 add-to-feed
                                 :p 6)}
       (feed-building/bolts)))))

(defn run-local! [& {debug "debug" workers "workers"
                     report-local-stats "report-local-stats"
                     :or {debug "false" workers "4" report-local-stats "false"}}]
  (let [drpc (LocalDRPC.)]
    (doto (LocalCluster.)
      (.submitTopology "story"
                       (merge
                        standard-topology-config
                        {TOPOLOGY-DEBUG (Boolean/parseBoolean debug)
                         TOPOLOGY-WORKERS (Integer/parseInt workers)})
                      (feed-generation-topology drpc)))
    (local-drpc-server/run! drpc (config/local-drpc-port))
    (when (Boolean/parseBoolean report-local-stats)
      (report-to-console 10))))


(comment
  (def c (LocalCluster.))
  (def d (LocalDRPC.))
  (def dr (local-drpc-server/run! d 3772))
  (.submitTopology c "build-feed" {TOPOLOGY-DEBUG true} (feed-generation-topology d))
  (import 'backtype.storm.utils.DRPCClient)
  (def dc (DRPCClient. "localhost" 3772))
  (.execute dc "build-feed" "47")
  (.shutdown c)
  (.stop dr)

  ;; lein run -m risingtide.storm.core/run-local!
  ;; brooklyn:
  ;; User.inject_listing_story(:listing_liked, 2, Listing.find(23))
  )
