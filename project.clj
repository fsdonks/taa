(defproject taa "0.0.1-SNAPSHOT"
  :description "FIXME: write description"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 ;;Exclude tools.reader.  Else, an error occurs.
                 [spork "0.2.1.4-SNAPSHOT" :exclusions [org.clojure/tools.reader]]
                 [demand_builder "0.1.1-SNAPSHOT"]
                 ;;Used docjure proper instead of spork.util.excel.docjure because
                 ;;the latest version returns nil for blank cells.  blank cells were
                 ;;filtered out in older versions.  This allows us to copy  the data as
                 ;;is from an xlsx worksheet and then copy the same data to a tab
                 ;;delimited text file, similar to Excel->Save As->tab delimited text
                 ;;file in order to save FORGE SRC by day for demand builder.
                 [dk.ative/docjure "1.16.0"]
                 ;;currently, marathon is not pushed to clojars to
                 ;;need to lein install from the marathon repo first
                 [marathon "4.2.3-SNAPSHOT"]]
  :repl-options {:init-ns taa.core}
  ;;This stuff below will allow us to build a runnable jar that
  ;;includes all dependencies by calling
  ;;lein with-profile uberjar capsule
  ;;from this repo
  :profiles {;;load our tests from resources just like in the uberjar
             :dev {:resource-paths ["test/resources"]}
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
             :execution {:runtime {:jvm-args ["-Xmx4g"]}} }
)
