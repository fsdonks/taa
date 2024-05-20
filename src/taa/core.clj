(ns taa.core
  ;;Require both capacity and requirements here so that the user can
  ;;call functions in either from this init namespace.
  (:require [taa [capacity :as capacity] 
             [requirements :as requirements]
             [demandanalysis :as analysis]]
            [marathon.analysis.random :as random])
  (:import [java.net InetAddress]))

(defmacro time-s
  "Evaluates expr and prints the time it took.  Returns the value of
  expr. Binds the elapsed time to return-time so that we can inspect
  it later if we lost the prn. Just a copy of clojure.core/time."
  {:added "1.0"}
  [expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr
         elapsed# (/ (double (- (. System (nanoTime))
                       start#)) 1000000000.0)]
     (prn (str "Elapsed time: " elapsed#  " seconds"))
     (def returned-time elapsed#)
     ret#))

;;big-srcs undefined in monkey patch.
;;moved to args where used.

;;Intent is for the user to rebind this and the inputs and outputs to
;;the taa stuff is located in one directory.
(def inputs-outputs-path "test-output/")
(defn m4-path [input-map demand-name]
  (str (:resources-root input-map) "m4_book_" demand-name ".xlsx"))

(defn rc-run-prep [input-map]
  (assoc input-map
         :upper 1.5
         :upper-rc 1.5
         :lower-rc 0
         :min-distance 5
         :include-no-demand false))

(defn rc-runs [big-srcs input-map filter-big? comp-name demand-name i threads]
  (capacity/do-taa-runs (m4-path input-map demand-name)
    (assoc (rc-run-prep input-map)
         :transform-proj (capacity/supply-src-filter big-srcs filter-big?)
         :reps 1
         :seed (rand Long/MAX_VALUE)
         :identifier (str demand-name "_" (if filter-big? "bigs" "smalls")
                       "-" comp-name "-" i)
      :threads threads)))

(defn ra-runs [book-path input-map threads]
  (capacity/do-taa-runs book-path
    (assoc input-map
      :upper 1
      :upper-rc 1
      :lower-rc 1
      :min-distance 0
      :threads threads)))

(defn base-only-runs [book-path {:keys [identifier] :as input-map} threads]
  (capacity/do-taa-runs book-path
    (assoc input-map
      :identifier (str identifier "-base")
      :upper 1
      :lower 1
      :upper-rc 1
      :lower-rc 1
      :min-distance 0
      :threads threads)))

;;to do 1/4 of the reps on one machine, do rep-fraction= 1/4
;;to do those 1/4 of the reps two times, use (range 2) for the rep-indices.
;;file-tag is some descriptor for these set of runs to include in the filename
;;threads is the number of cores on the machine that you are
;;running on.
;;rc-runs? is true or false depending on if you are doing the ra*rc runs or
;;just the ra runs.

;;This function will spit out one results.txt file for each fraction of reps.

;;NOTE - this should be obviated with automatic run distribution and incremental
;;patch.
(defn variable-rep-runs [input-map rep-fraction rep-indices file-tag
                         threads rc-runs?]
  (let [identifier (:identifier input-map)
        ;;Used to write output files named by the specific computer.
        computer-name (.getHostName (InetAddress/getLocalHost))]
    (doseq [i rep-indices]
      (capacity/do-taa-runs (m4-path input-map identifier)
        (assoc (if rc-runs? (rc-run-prep input-map) input-map)
           ;;not used with :replicator
           ;;:reps num-reps
           :conj-proj {:replicator
                       (partial random/portion-of-reps rep-fraction)}
           :seed (rand Long/MAX_VALUE)
           :identifier (str identifier "_" file-tag "_"
                         i "-" computer-name)
           :threads threads)))))
