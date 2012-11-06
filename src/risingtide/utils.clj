(ns risingtide.utils
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [risingtide
             [core :refer [log-err]]
             [config :as config]
             [redis :as redis]]
            [risingtide.action.persist.solr :as solr]))

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
