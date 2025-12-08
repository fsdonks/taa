(defproject taa "0.0.26-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [marathon "4.2.20-SNAPSHOT"]
                 [m4peer   "0.1.2-BETA"]
                 [taapost "1f2ae57d018a9cba3453fd7e4e2af02927bf8753" #_"0.1.2-SNAPSHOT"
                  #_#_:exclusions [jfree/jfreechart jfree/jfreechart com.taoensso/nippy
                               commons-codec]]
                 ]
  #_#_
  :repl-options {:init-ns taa.core}

  ;;allow testing ns and data to be resolved for transitive dep.
  :source-paths   ["src" "test"]
  :resource-paths ["test/resources"]
  :profiles {;;load our tests from resources just like in the uberjar
             :dev {:resource-paths ["test/resources"]
                   :jvm-opts ^:replace ["-Xmx8g" "-XX:+UseParallelGC"]
                   :source-paths []
                   }}
  :plugins [[reifyhealth/lein-git-down "0.4.1"]]
  :middleware [lein-git-down.plugin/inject-properties]
  :repositories [["public-github" {:url "git://github.com"}]]
  :git-down {marathon  {:coordinates  fsdonks/m4}
             demand_builder  {:coordinates  fsdonks/demand_builder}
             m4peer {:coordinates  fsdonks/m4peer}
             taapost {:coordinates fsdonks/taapost}})
