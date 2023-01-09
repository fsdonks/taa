(defproject taa "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 ;;Exclude tools.reader.  Else, an error occurs.
                 [spork "0.2.1.6-SNAPSHOT" :exclusions
                  [org.clojure/tools.reader
                   ;;need to exclude for tech.ml.dataset
                   ;;com.taoensso/nippy                   
                   ]]
                 [demand_builder "0.1.1-SNAPSHOT"]
                 ;;currently, marathon is not pushed to clojars to
                 ;;need to lein install from the marathon repo first
                 [marathon "4.2.4-SNAPSHOT"]
                 ;;not in clojars either, this is fs-c's fork.
                 [smiletest "0.1.0-SNAPSHOT"]
                 ;[com.clojure-goes-fast/clj-memory-meter "0.2.1"]
                 ;[techascent/tech.ml.dataset "7.000-beta-2"]
                 ]
  :repl-options {:init-ns taa.core}
  ;;This stuff below will allow us to build a runnable jar that
  ;;includes all dependencies by calling
  ;;lein with-profile uberjar capsule
  ;;from this repo
  ;;Then you can run the capsule from the terminal with
  ;;java -jar taa-0.0.1.jar and call functions from there like in
  ;;taa.core-test.
  :profiles {;;load our tests from resources just like in the uberjar
             :dev {:resource-paths ["test/resources"]
                                        :jvm-opts ^:replace ["-Xmx8g"]
                   :source-paths ["../marathon/src"]
                   }
             :uberjar {:aot [taa.main]
                       :main  taa.main
                       :jvm-opts ^:replace ["-Xmx1000m" "-XX:NewSize=200m" "-server"]
                       :plugins [[lein-capsule "0.2.1"]]
                       ;;put the test dir in here so we can run tests from the capsule
                       :source-paths ["src" "test"]
                       :resource-paths ["test/resources"]
                       }}
  ;;default is thin.  This should include all dependencies in the capsule.
  :capsule {:types {:fat {}}
             :execution {:runtime {:jvm-args ["-Xmx8g"]}} }
)
