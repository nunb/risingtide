(ns risingtide.reports
  (:require [clojure.set :refer [difference]]
            [risingtide
             [active-users :refer [active-user-key-pattern]]
             [key :refer [user-feed]]
             [redis :as redis]]
            [incanter.stats :refer [quantile]]))


(defn jedis-map-in-batches
  ([redis f coll n]
     (apply concat
            (map
             (fn [batch] (redis/with-jedis* redis
                          (fn [jedis]
                            (doall (map #(f jedis %) batch)))))
             (partition-all n coll))))
  ([redis f coll]
     (jedis-map-in-batches redis f coll 1000)))

;; active users
(defn active-users [redii]
  (let [keys
        (redis/with-jedis* (redii :active-users)
          (fn [jedis]
            (.keys jedis (active-user-key-pattern))))]
    (jedis-map-in-batches
     (redii :active-users)
     (fn [jedis key] [(nth (.split key ":") 2) (try (.ttl jedis key) (catch Exception e nil))])
     keys)))


;; active feeds
(defn active-feeds [redii]
  (apply
   concat
   (for [server [:card-feeds-1]]
     (let [keys (redis/with-jedis* (redii server)
                  (fn [jedis]
                    (.keys jedis (user-feed "*"))))]
       (jedis-map-in-batches
        (redii server)
        (fn [jedis key] [(nth (.split key ":") 3) (.zcard jedis key)])
        keys)))))

(defn report
  ([redii]
     (let [actives-and-ttls (active-users redii)
           feeds-and-sizes (active-feeds redii)
           actives (map first actives-and-ttls)
           feeds (map first feeds-and-sizes)
           ttls (map second actives-and-ttls)
           feed-sizes (map second feeds-and-sizes)]
       {:active-users (count actives)
        :active-feeds (count feeds)
        :active-ttl-quantiles (quantile ttls)
        :feed-size-quantiles (quantile feed-sizes)
        :small-feeds (map first (filter (fn [[id size]] (< size 36)) feeds-and-sizes))
        :no-expiry-active-users (map first (filter (fn [[id ttl]] (= -1 ttl)) actives-and-ttls))
        :dangling-feeds (difference (set feeds) (set actives))}))
  ([] (report (redis/redii))))

(defn small-feeds
  ([redii]
     (filter (fn [[_ size]] (< size 100)) (active-feeds redii)))
  ([] (small-feeds (redis/redii))))

(comment
  (require 'risingtide.reports)
  (in-ns 'risingtide.reports)

  (active-users (redis/redii))
  (active-feeds (redis/redii))
  (report (redis/redii))

  (def r  (report (redis/redii)))

  ;; clean up no expiry actives
  (redis/with-jedis* ((redis/redii) :active-users)
    (fn [jedis]
      (.del jedis
       (into-array String
                   (map #(str "magp:act:"%) (:no-expiry-actives r))))))

  ;; clean up dangling feeds
  (redis/with-jedis* ((redis/redii) :card-feeds-1)
    (fn [jedis]
      (.del jedis
            (into-array String
                        (map #(str "magp:f:u:"%":c") (:dangling-feeds r))))))

  )