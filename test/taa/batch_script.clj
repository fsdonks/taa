;;A simplistic script file for HPC scripting on array jobs on
;;PBS or other systems.
(ns taa.batch-script
  (:require [spork.util [io :as io]]
            [taa [core :as core]]))

(defn env [x] (System/getEnv x))
;;assuming we're in a PBS job array submission deal.
(def job-index (env "PBS_ARRAY_INDEX"))

;;just load up a relative file. assuming we're running this from a clojure repl
;;through leiningen in the taa project, the below path is accurate.

;;actual usage needs to change relative to actual path on production system.
(load-file (io/file-path "./test/taa/batch_test.clj"))
;;alias the namespace we just loaded for convenience.
(require '[taa.batch-test :as batch])
;;an alternate simplified assumption: scripts and data are in the cwd.
#_(load-file "batch_test.clj")

;;perform a run for the "AP" designs.
;;assume we have a run plan in cwd/plan.edn:
(defn run-me []
  (core/run-from-plan "plan.edn" job-index batch/input-map-AP))

;;then we can leverage a bash invocation like ./batch.pbs
