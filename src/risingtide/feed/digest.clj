(ns risingtide.feed.digest
  (require [risingtide.story :refer [StoryDigest type-sym score with-score] :as story]
           [risingtide.feed :refer [Feed] :as feed]
           [risingtide.config :as config]
           [clojure.tools.logging :as log]
           [clojure.set :as set])
  (import [risingtide.story TagLikedStory ListingLikedStory ListingCommentedStory ListingActivatedStory ListingSoldStory ListingSharedStory MultiActorStory MultiActionStory MultiActorMultiActionStory MultiListingStory]))

(defn- listing-digest-index-for-stories [stories]
  (reduce (fn [m story]
            (assoc m
              :actors (conj (:actors m) (:actor-id story))
              :actions (conj (:actions m) (type-sym story))))
          {:actors #{} :actions #{}}
          stories))

(defn- mama-actions
  [stories]
  (reduce (fn [m story] (assoc m (type-sym story) (set (conj (m (type-sym story)) (:actor-id story)))))
          {} stories))

(deftype ListingStorySet [stories]
  StoryDigest
  (add [this story]
    ;; prereqs: story must match listing id of this
    (let [new-stories (set (conj stories story))
          index (listing-digest-index-for-stories new-stories)
          listing-id (:listing-id story)]
      (case [(> (count (:actors index)) 1) (> (count (:actions index)) 1)]
        [true true] (with-score (story/->MultiActorMultiActionStory
                                 listing-id (mama-actions new-stories))
                       (score story))
        [true false] (with-score (story/->MultiActorStory listing-id (type-sym story) (set (:actors index))) (score story))
        [false true] (with-score (story/->MultiActionStory listing-id (:actor-id story) (set (:actions index))) (score story))
        [false false] (ListingStorySet. new-stories)))))

(deftype ActorStorySet [stories]
  StoryDigest
  (add [this story]
    ;; prereqs: story must match actor id, action of this
    (let [new-stories (set (conj stories story))
          listing-ids (set (map :listing-id new-stories))]
      (if (>= (count listing-ids) config/single-actor-digest-story-min)
        (with-score (story/->MultiListingStory (:actor-id story) (type-sym story) listing-ids)  (score story))
        (ActorStorySet. new-stories)))))

;;;;;;; digest indexing ;;;;;;;
;;; add a big ass comment here explaining digest indexing

(defn- listing-index-path [story]
  [:listings (:listing-id story)])

(defn- actor-index-path [story]
  [:actors (:actor-id story) (type-sym story)])

(defn- add-story [existing-digest story init-digest]
  "Given the value of an existing digest for the given story,
either add the story to the digest or return a new digest object
containing the story by passing the story to init-digest."
  (if existing-digest
    (story/add existing-digest story)
    (init-digest #{story})))

(defn- add-digest [existing-digest new-digest]
  "Given the value of an existing digest and a new digest value, reconcile the two.
This should only happen when loading digest stories from disk"
  (when existing-digest (log/warn "trying to add digest story to index but something's already here: " existing-digest " so I'll use the newer story: " new-digest))
  new-digest)

(defprotocol Indexable
  (index [story index]
    "Given a story and an index, update the index appropriately."))

(defn add-to-index [idx story]
  (index story idx))

;; extend Indexable to the story classes

(defn- index-with [index & args]
  (doseq [type args]
    (extend type Indexable
            {:index index})))

(index-with
 (fn [story index]
   (-> index
       (update-in (listing-index-path story) add-story story ->ListingStorySet)
       (update-in (actor-index-path story) add-story story ->ActorStorySet)))
 ListingLikedStory ListingCommentedStory ListingActivatedStory ListingSoldStory ListingSharedStory)

(index-with
 (fn [story index]
   (assoc index :nodigest (cons story (:nodigest index))))
 TagLikedStory)

(index-with
 (fn [story index]
   (update-in (listing-index-path story) add-digest story))
 MultiActorMultiActionStory MultiActionStory MultiActorStory)

(index-with
 (fn [story index]
   (update-in (actor-index-path story) add-digest story))
 MultiListingStory)

;;;; ToStories - converting a digesting index to a feed ;;;;

(defprotocol ToStories
  (to-stories [leaf]
    "Given a leaf in the index, return a tuple of sets of single stories and digest stories
to be inserted into the feed."))

;; extend ToStories to the story classes

(defn- to-stories-with [to-stories & args]
  (doseq [type args]
    (extend type ToStories
            {:to-stories to-stories})))

(to-stories-with
 (fn [leaf] [#{leaf} #{}])
 MultiActorMultiActionStory MultiActionStory MultiActorStory MultiListingStory)

(to-stories-with
 (fn [leaf] [#{} (.stories leaf)])
 ListingStorySet ActorStorySet)

(defn- reduce-digests [digests]
  "Given a set of digest index leaves, return a tuple of stories and digest stories from those leaves."
  (reduce (fn [[digest-m single-m] [digest single]] [(set/union digest-m digest) (set/union single-m single)])
          [] (map to-stories digests)))

(defn feed-from-index
  [digesting-index]
  (let [[listing-digests listing-stories] (reduce-digests (vals (:listings digesting-index)))
        [actor-digests actor-stories] (reduce-digests (apply concat (map vals (vals (:actors digesting-index)))))]
    (concat (:nodigest digesting-index)
            ;; union all digest stories
            (set/union listing-digests actor-digests)
            ;; take the intersection of single story sets to ensure we only get stories
            ;; that are not in any digest stories
            (set/intersection listing-stories actor-stories))))

;;;; Tracking min/max timestamps ;;;;

(defn- updated-timestamp [existing-timestamp new-timestamp comparator]
  (if existing-timestamp
    (comparator existing-timestamp new-timestamp)
    new-timestamp))

(defn- update-timestamps [index story]
  (let [m (meta index)]
   (with-meta index
     (merge m {:max-ts (updated-timestamp (:max-ts m) (story/score story) max)
               :min-ts (updated-timestamp (:min-ts m) (story/score story) min)}))))

(defn min-ts [story-index]
  (:min-ts (meta story-index)))

(defn max-ts [story-index]
  (:max-ts (meta story-index)))

;;;; Digest Feed Implementation ;;;;

(defn new-index [] {})

(deftype DigestFeed [idx]
  Feed
  (add [this story]
    (DigestFeed. (-> story
                     (index (or idx (new-index)))
                     (update-timestamps story))))
  (min-timestamp [feed] (min-ts idx))
  (max-timestamp [feed] (max-ts idx))
  clojure.lang.Seqable
  (seq [this] (feed-from-index (or idx (new-index)))))

(defn new-digest-feed
  []
  (->DigestFeed (new-index)))



(comment
  ;;expiration
  (defn expired?
  [feed-key story]
  (< (:score story) (expiration-threshold feed-key)))

(defn filter-expired-stories-from-set
  [feed-key s]
  (let [s (filter #(not (expired? feed-key %)) s)]
    (when (not (empty? s)) (into #{} s))))

(defn expire-feed-index
  [feed-key feed-index]
  (reduce (fn [m [key value]]
            (let [new-value (if (map? value)
                              (when (not (expired? feed-key value)) value)
                              (filter-expired-stories-from-set feed-key value))]
              (if new-value (assoc m key new-value) (dissoc m key))))
          {} feed-index))

(defn expire-nodigest
  [feed-key feed-indexes]
  (assoc feed-indexes :nodigest (filter-expired-stories-from-set feed-key (:nodigest feed-indexes))))

(defn expire-feed-indexes
  [feed-key feed-indexes]
  (assoc
   (expire-nodigest
    feed-key
    (reduce (fn [f key] (assoc f key (expire-feed-index feed-key (f key)))) feed-indexes [:listings :actors]))
   :min (expiration-threshold feed-key)))

(defn clean-feed-index
  [feed-key feed-index]
  (dissoc (expire-feed-indexes feed-key feed-index) :dirty))


  )