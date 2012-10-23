(ns risingtide.storm.story-spout
  (:require
   [risingtide.core :refer [now]]
   [risingtide.v2.story :as story]
   [risingtide
    [config :as config]
    [redis :as redis]]

   [backtype.storm [clojure :refer [defspout spout emit-spout!]]]

   [clojure.data.json :as json]
   [clojure.string :as str]
   [clojure.set :refer [rename-keys]]))

(defn dash-case-keys [story]
  (into {} (map (fn [[k v]] [(keyword (str/replace (name k) "_" "-")) v]) story)))

(defn story-to-record [story]
  (story/with-score
   ((story/story-factory-for (keyword (:type story)))
    (-> story
        dash-case-keys
        (dissoc :type)
        (assoc :feed (map keyword (:feed story)))))
   (or (:timestamp story) (now))))

(defn story-from-resque [story]
  (let [json (json/read-json story)]
    (when (= "Stories::Create" (:class json))
      (prn json)
      (story-to-record (first (:args json))))))

(defspout resque-spout ["story"]
  [conf context collector]
  (let [pool (redis/redis (config/redis-config))]
   (spout
    (nextTuple []
     (when-let [s (let [r (.getResource pool)]
                    (try
                      (or
                       (.lpop r "resque:queue:rising_tide_priority")
                       (.lpop r "resque:queue:rising_tide_stories"))
                      (finally (.returnResource pool r))))]
       (let [story (story-from-resque s)]
         (prn "PROCESSING "s" with meta"(meta s))
        (emit-spout! collector [story]))))
    (ack [id]
         ;; You only need to define this method for reliable spouts
         ;; (such as one that reads off of a queue like Kestrel)
         ;; This is an unreliable spout, so it does nothing here
         ))))
