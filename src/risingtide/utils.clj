(ns risingtide.utils
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [risingtide
             [core :refer [log-err]]
             [config :as config]
             [redis :as redis]
             [active-users :refer [active-user-key]]]
            [clj-time
             [core :refer [months]]
             [format :refer [parse formatter]]]
            [risingtide.action.persist.solr :as solr]
            [risingtide.reports :refer [report]]
            [risingtide.feed.persist :refer [delete-feeds!]]))

;; migrate staging keys to development.

(defn convert-redis-keys-from-staging-to-dev!
  ([redii]
     (doseq [[k pool] redii]
      (if (= :development config/env)
        (redis/with-jedis* pool
          (fn [jedis]
            (for [key (.keys jedis "*")]
              (.rename jedis key (.replaceFirst key "mags" "magd")))))
        (prn "holy shit. you really, really don't want to rename keys in" config/env))))
  ([] (convert-redis-keys-from-staging-to-dev! (redis/redii))))


(defn load-actions-into-solr! [dumpfile]
  "parse an action dumpfile and load the results into the actions solr ;;;;

run like:

    lein run -m risingtide.utils/load-actions-into-solr! ~/actions.log
"
  (with-open [rdr (io/reader dumpfile)]
    (let [solr-conn (solr/connection)]
      (log/info "Reading actions from "dumpfile" to solr at "(config/action-solr))
      (apply solr/save!
       solr-conn
       (map (fn [line-number line]
              (try
                (json/read-json line)
                (catch Throwable t
                  (log-err (str "Failed to load line number "line-number" with error:") t *ns*)
                  (throw t))))
            (range)
            (line-seq rdr))))))


(defn add-timestamps-to-stories [log]
  "Given a recent stories log, add a timestamp based on the syslog timestamp

Should never actually be needed ever again, since we log timestamps now, but commit for posterity
"
  (spit "timestamped-stories.log"
   (clojure.string/join
    "\n"
    (for [[time story] (map #(vec (.split % " rt-storm4 java: ")) (.split (slurp log) "\n"))]
      (do
       (prn story)
       (json/json-str
        (assoc (json/read-json story) :timestamp
               (long (/ (.getMillis (parse (formatter "YYYY MMM dd HH:mm:ss") (str "2012 "time))) 1000)))))))))

;;;; define "runnable jobs" suitable for using with lein run ;;;;
;;
;; right now, this just creates a function name prefixed with run- and
;; ensures agents are shut down after the given forms are executed

(defmacro defrun
  [name & forms]
  `(defn ~(symbol (str "run-" name))
     []
     (let [r# (do ~@forms)]
       (shutdown-agents)
       r#)))

(defrun convert-redis-keys-from-staging-to-dev!
  (convert-redis-keys-from-staging-to-dev!))

(defn clean-dangling-feeds
  "sometimes feeds are left in redis after the corresponding active user has been removed
from the active users list. this finds and deletes such feeds."
  []
  (delete-feeds! (redis/redii) (:dangling-feeds (report))))

(defn expire-actives-with-no-expiry
  "we appear to get active users with no expiry. find users for whom this is the
case and set their expiry to 1 day from now"
  []
  (redis/with-jedis* ((redis/redii) :active-users)
    (fn [jedis]
      (doseq [id (:no-expiry-active-users (report))]
        (.expire jedis (active-user-key id) (* 60 60 24))))))

(defn flush-old-actions-from-solr
  "delete all actions more than 2 months old from the solr instance
we use to build new feeds"
  []
  (solr/delete-actions-older-than! (solr/connection) (-> 2 months)))
