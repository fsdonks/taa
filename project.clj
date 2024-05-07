(defproject taa "0.0.20-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [marathon "4e31f03" #_"4.2.15-SNAPSHOT"]
                 ;[com.clojure-goes-fast/clj-memory-meter "0.2.1"]
                 ;[techascent/tech.ml.dataset "7.000-beta-2"]
                 ]
  :repl-options {#_#_:init-ns taa.core
                 :timeout 120000}

  ;;allow testing ns and data to be resolved for transitive dep.
  :source-paths   ["src" "test"]
  :resource-paths ["test/resources"]
  :profiles {;;load our tests from resources just like in the uberjar
             :dev {:resource-paths ["test/resources"]
                   :jvm-opts ^:replace ["-Xmx8g"]
                   :source-paths []
                   }}
  :plugins [[reifyhealth/lein-git-down "0.4.1"]]
  :middleware [lein-git-down.plugin/inject-properties]
  :repositories [["public-github" {:url "git://github.com"}]]
  :git-down {marathon  {:coordinates  fsdonks/m4}
             demand_builder  {:coordinates  fsdonks/demand_builder}})
