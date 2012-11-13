(load-file "src/risingtide/version.clj")

(defproject risingtide risingtide.version/version
  :description "He who reads the stories writes the feeds."
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/data.json "0.1.1"]
                 [org.clojure/tools.logging "0.2.3"]

                 [clj-logging-config "1.9.6"]
                 [redis.clients/jedis "2.0.0"]
                 [korma "0.3.0-beta11"]
                 [mysql/mysql-connector-java "5.1.20"]
                 [org.syslog4j/syslog4j "0.9.30"]
                 [net.java.dev.jna/jna "3.4.0"]

                 [compojure "1.1.0"]
                 [ring/ring-jetty-adapter "1.1.6"]
                 [enlive "1.0.0"]

                 [utahstreetlabs/clojure-solr "0.3.0"]
                 [risingtide-model "2.0.2"]]
  :java-source-paths ["java-src"]
  :profiles {:dev {:dependencies [[midje "1.4.0"]
                                  ;; storm dependency only in dev
                                  ;; cause production storm cluster
                                  ;; provide it
                                  [storm "0.8.1" :exclusions [org.slf4j/slf4j-api org.slf4j/slf4j-log4j12]]]}}
  :run-aliases {:convert-redis-keys-from-staging-to-dev! risingtide.utils/run-convert-redis-keys-from-staging-to-dev!}
  :main risingtide
  :min-lein-version "2.0.0"
  :plugins [[lein-midje "2.0.0-SNAPSHOT"]]

  :repositories {"usl-snapshots"
                 {:url "http://ci.copious.com:8082/nexus/content/repositories/snapshots/"
                  :username "deployment" :password "Q5Erm4JqFppGSf"}
                 "usl-releases"
                 {:url "http://ci.copious.com:8082/nexus/content/repositories/releases/"
                  :username "deployment" :password "Q5Erm4JqFppGSf"}}

  :aot [risingtide.storm.FeedTopology])










