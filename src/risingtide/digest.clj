(ns risingtide.digest
  (:use risingtide.core)
  (:require [risingtide.stories :as story]
            [risingtide.feed :as feed]
            [risingtide.config :as config]
            [risingtide.key :as key]
            [clojure.tools.logging :as log]
            [accession.core :as redis]
            [clojure.set :as set]))

(def ^:dynamic *cache-ttl* (* 6 60 60))

(defn feed-load-cmd
  ([feed-key since until]
     {:pre [(not (nil? feed-key)) (>= since 0) (pos? until)]}
     (redis/zrangebyscore feed-key since until "WITHSCORES"))
  ([feed-key since] (feed-load-cmd feed-key since (now)))
  ([feed-key] (feed-load-cmd feed-key 0)))

(defn parse-stories-and-scores
  [stories-and-scores]
  (for [[story score] (partition 2 stories-and-scores)]
    (assoc (story/decode story) :score (Long. score))))

(defn load-feed
  [redii feed-key since until]
  (feed/with-connection-for-feed redii feed-key
    [connection]
    (parse-stories-and-scores
     (redis/with-connection connection
       (feed-load-cmd feed-key since until)))))

(defmulti add-to-listing-digest :type)

(defmethod add-to-listing-digest "listing_multi_action" [current story]
  {:pre [(= (:listing_id current) (:listing_id story))]}
  (if (= (:actor_id current) (:actor_id story))
    (story/update-digest current
      :types (distinct (conj (:types current) (:type story)))
      :score (:score story))
    (story/multi-actor-multi-action-digest
     (:listing_id current)
     (reduce (fn [h type] (assoc h type (conj (h type) (:actor_id current))))
             {(:type story) [(:actor_id story)]}
             (:types current))
     (:score story))))

(defmethod add-to-listing-digest "listing_multi_actor" [current story]
   {:pre [(= (:listing_id current) (:listing_id story))]}
   (if (= (:type story) (:action current))
     (story/update-digest current
       :actor_ids (distinct (conj (:actor_ids current) (:actor_id story)))
       :score (:score story))
     (story/multi-actor-multi-action-digest
      (:listing_id current)
      {(:action current) (:actor_ids current)
       (:type story) [(:actor_id story)]}
      (:score story))))

(defmethod add-to-listing-digest "listing_multi_actor_multi_action" [current story]
  {:pre [(= (:listing_id current) (:listing_id story))]}
  (let [path [:types (:type story)]]
    (story/update-digest
     (assoc-in current path (distinct (conj (get-in current path) (:actor_id story))))
     :score (:score story))))

(defn- listing-digest-index-for-stories [stories]
  (reduce (fn [m story]
            (assoc m
              :actors (conj (:actors m) (:actor_id story))
              :types (conj (:types m) (:type story))))
          {:actors #{} :types #{}}
          stories))

(defn- mama-types
  [stories]
  (reduce (fn [m story] (assoc m (:type story) (conj (m (:type story)) (:actor_id story))))
          {} stories))

(defn maybe-create-new-listing-digest
  "all stories MUST have the same listing id"
  [current story]
  (let [stories (conj current story)
        index (listing-digest-index-for-stories stories)
        listing-id (:listing_id story)]
    (case [(> (count (:actors index)) 1) (> (count (:types index)) 1)]
      [true true] (story/multi-actor-multi-action-digest
                   listing-id (mama-types stories) (:score story))
      [true false] (story/multi-actor-digest listing-id (:type story) (vec (:actors index)) (:score story))
      [false true] (story/multi-action-digest listing-id (:actor_id story) (vec (:types index)) (:score story))
      [false false] stories)))

(defn- classify-current
  [current]
  (when current
    (if (story/digest-story? current)
      :digest
      (if (set? current)
        :set
        (throw (Exception. (str "corrupt cache! contains " current)))))))

(defn- listings-story-aggregator [current story]
  {:pre [(not (nil? story)) (not (nil? (:listing_id story)))]}
  (case [(classify-current current) (boolean (story/digest-story? story))]
    [nil false] #{story}
    [:digest false] (add-to-listing-digest current story)
    [:set false] (maybe-create-new-listing-digest current story)

    ;; happens when loading a predigested feed
    [nil true] story

    ;; pathological states, try to repair
    [:digest true] (do (log/warn "duplicate digest stories! " current story "using newer") story)
    [:set true] (do (log/warn "undigested and digested coexist! " current story "using digest") story)))

(defn add-story-to-listings-index
  [digesting-index story]
  (if (:listing_id story)
    (let [path [:listings (:listing_id story)]]
      (assoc-in digesting-index path
                (listings-story-aggregator (get-in digesting-index path) story)))
    digesting-index))

(defn add-to-actor-digest [current story]
  {:pre [(= (:actor_id current) (:actor_id story))]}
  (story/update-digest current
    :listing_ids (distinct (conj (:listing_ids current) (:listing_id story)))
    :score (:score story)))

(defn- actor-digest-index-for-stories [stories]
  (reduce (fn [m story]
            (assoc m
              :listings (conj (:listings m) (:listing_id story))))
          {:listings #{}}
          stories))

(defn maybe-create-new-actor-digest
  "all stories MUST have the same actor id"
  [current story]
  (let [stories (conj current story)
        index (actor-digest-index-for-stories stories)
        actor-id (:actor_id story)]
    (if (> (count (:listings index)) 15) ;;XXX: constant
      (story/multi-listing-digest actor-id (:type story) (vec (:listings index)) (:score story))
      stories)))

(defn actor-story-aggregator
  [current story]
  (case [(classify-current current) (boolean (story/digest-story? story))]
    [nil false] #{story}
    [:digest false] (add-to-actor-digest current story)
    [:set false] (maybe-create-new-actor-digest current story)

    ;; happens when loading a predigested feed
    [nil true] story

    ;; pathological states, try to repair
    [:digest true] (do (log/warn "duplicate digest stories! " current story "using newer") story)
    [:set true] (do (log/warn "undigested and digested coexist! " current story "using digest") story)))

(defn add-story-to-actors-index
  [digesting-index story]
  (if (:actor_id story)
    (let [path [:actors (:actor_id story)]]
      (assoc-in digesting-index path
                (actor-story-aggregator (get-in digesting-index path) story)))
    digesting-index))

(defn add-story
  [digesting-index story]
  (if (and (:listing_id story) (:actor_id story))
    (-> digesting-index
        (add-story-to-listings-index story)
        (add-story-to-actors-index story))
    (assoc digesting-index :nodigest (cons story (:nodigest digesting-index)))))

(defn index-predigested-feed
  [feed]
  (reduce add-story {:listings {} :actors {}} feed))

;;; cache

(def feed-cache (atom {}))

(defn reset-cache! []
  (swap! feed-cache (constantly {}) feed-cache))

(defn get-or-load-feed-atom
  [cache-atom redii feed-key ttl]
  (or (@cache-atom feed-key)
      (let [feed-index-atom (atom (index-predigested-feed (load-feed redii feed-key (- (now) ttl) (now))))]
        (swap! cache-atom (fn [cache] (assoc cache feed-key feed-index-atom)))
        feed-index-atom)))

(defn mark-dirty
  [feed-index]
  (assoc feed-index :dirty true))

(defn add-story-to-feed-index
  [feed-index-atom story]
  (swap! feed-index-atom (fn [feed-index] (mark-dirty (add-story feed-index story)))))

(defn add-story-to-feed-cache
  ([cache-atom redii feed-key story]
     (add-story-to-feed-index (get-or-load-feed-atom cache-atom redii feed-key *cache-ttl*) story))
  ([redii feed-key story] (add-story-to-feed-cache feed-cache redii feed-key story)))

(defn replace-feed-index!
  ([cache-atom feed-key feed-index]
     (let [new-feed-index (mark-dirty feed-index)]
       (if (@cache-atom feed-key)
         (swap! (@cache-atom feed-key) (fn [_] new-feed-index))
         (swap! cache-atom (fn [cache] (assoc cache feed-key (atom new-feed-index)))))))
  ([feed-key story] (replace-feed-index! feed-cache feed-key story)))


;;; creating a feed from the digesting index

(defn- bucket-story
  [m story]
  (if (map? story)
    (assoc m :digest (conj (:digest m) story))
    (assoc m :single (set/union (:single m) story))))

(defn feed-from-index
  [digesting-index]
  (let [bucketed-stories
        [(reduce bucket-story {} (vals (:listings digesting-index)))
         (reduce bucket-story {} (vals (:actors digesting-index)))]]
    (concat (:nodigest digesting-index)
     (apply set/union (map :digest bucketed-stories))
     (apply set/intersection (map :single bucketed-stories)))))

;;; writing out the cache

(defn write-feed-index!
  [redii feed-key feed-atom]
  (let [stories (feed-from-index feed-atom)
        scores (map :score stories)
        low-score (apply min scores)
        high-score (apply max scores)]
    (feed/with-connection-for-feed redii feed-key
      [connection]
      (apply redis/with-connection connection
             (feed/replace-feed-head-query feed-key stories low-score high-score)))))

(defn expired?
  [story]
  (< (:score story) (- (now) *cache-ttl*)))

(defn expire-feed-index
  [feed-index]
  (reduce (fn [f [key value]]
            (assoc f key
                   (if (map? value)
                     (when (not (expired? value)) value)
                     (let [s (filter #(not (expired? %)) value)]
                       (when (not (empty? s)) (apply hash-set s))))))
          {} feed-index))

(defn expire-feed-indexes
  [feed-indexes]
  (reduce (fn [f key] (assoc f key (expire-feed-index (f key)))) feed-indexes [:listings :actors]))

(defn clean-feed-index
  [feed-index]
  (dissoc (expire-feed-indexes feed-index) :dirty))

(defn write-feed-atom!
  [redii key feed-atom]
  (swap! feed-atom (fn [feed-index]
                     (if (:dirty feed-index)
                       (do
                         (write-feed-index! redii key feed-index)
                         (clean-feed-index feed-index))
                       feed-index))))

(defn write-cache!
  ([cache-atom redii]
     (doall (pmap-in-batches
             (fn [[k v]]
               (try
                 (write-feed-atom! redii k v)
                 (catch Exception e
                   (log/error (str "exception flushing cache for" k) e)
                   (safe-print-stack-trace e))))
             @cache-atom)))
  ([redii] (write-cache! feed-cache redii)))

(defn cache-flusher
  "Given a cache, a redis config and an interval in seconds, start scheduling tasks with a fixed
delay of interval to flush cached feeds to redis.
"
  [cache-atom redii interval]
  (doto (java.util.concurrent.ScheduledThreadPoolExecutor. 1)
    (.scheduleWithFixedDelay
     #(bench "flushing cache"
             (try (write-cache! cache-atom redii)
                  (catch Exception e
                    (log/error "exception flushing cache" e)
                    (safe-print-stack-trace e))))
     interval interval java.util.concurrent.TimeUnit/SECONDS)))

;;;; feed building ;;;;

(defn zunion-withscores
  [redii story-keys limit & args]
  (nth
   (nth
    (redis/with-connection (:stories redii)
      (redis/multi)
      (apply redis/zunionstore "rtzuniontemp" story-keys args)
      (redis/zrange "rtzuniontemp" (- 0 limit) -1 "WITHSCORES")
      (redis/del "rtzuniontemp")
      (redis/exec))
    4) 1))

(defn- fetch-filter-digest-user-stories
  [redii feed-key]
  (let [[feed-type user-id] (key/type-user-id-from-feed-key feed-key)]
    (index-predigested-feed
     (feed/user-feed-stories
      (parse-stories-and-scores
       (zunion-withscores redii (feed/interesting-story-keys redii feed-type user-id)
                          1000 "AGGREGATE" "MIN"))))))

(defn- zadd-encode-stories
  [stories]
  (flatten (map #(vector (:score %) (story/encode %)) stories)))

(defn build! [redii feeds-to-build]
  (doall
   (map replace-feed-index! feeds-to-build
        (map #(fetch-filter-digest-user-stories redii %) feeds-to-build))))

(defn build-for-user!
  [redii user-id]
  (build! redii [(key/user-card-feed user-id) (key/user-network-feed user-id)]))
