(defproject taa "0.0.4-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [marathon "4.2.5-SNAPSHOT"]
                 ;;not in clojars either, this is fs-c's fork.
                 [smiletest "0.1.0-SNAPSHOT"]
                 ;[com.clojure-goes-fast/clj-memory-meter "0.2.1"]
                 ;[techascent/tech.ml.dataset "7.000-beta-2"]
                 ]
  :repl-options {:init-ns taa.core
                 :timeout 120000}
  ;;This stuff below will allow us to build a runnable jar that
  ;;includes all dependencies by calling
  ;;lein with-profile uberjar capsule
  ;;from this repo
  ;;Then you can run the capsule from the terminal with
  ;;java -jar taa-0.0.1.jar and call functions from there like in
  ;;taa.core-test.
  :source-paths ["src" "test"]
  :profiles {;;load our tests from resources just like in the uberjar
             :dev {:resource-paths ["test/resources"]
                                        :jvm-opts ^:replace ["-Xmx8g"]
                   :source-paths ["../marathon/src"]
                   }}
  :plugins [[reifyhealth/lein-git-down "0.4.1"]]
  :middleware [lein-git-down.plugin/inject-properties]
  :repositories [["public-github" {:url "git://github.com"}]]
  :git-down {marathon  {:coordinates  fsdonks/m4}
             smiletest {:coordinates  fs-tom/krigingdemo}})
