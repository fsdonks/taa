(ns taa.core
  ;;Require both capacity and requirements here so that the user can
  ;;call functions in either from this init namespace.
  (:require [taa [capacity :as capacity]
             [requirements :as requirements]
             [demandanalysis :as analysis]]
            [marathon.analysis.random :as random]))

;;big-srcs undefined in monkey patch.
;;inputs-outputs-path was undefined in the monkey patch.
(println [:WARNING '[taa.core inputs-outputs-path big-srcs computer-name] :STUBBED-WITH-PLACEHOLDERS])
(def inputs-outputs-path "")
(def big-srcs #{})
(def computer-name "")

;;temporary error to prevent usage until we get this sorted out.
(defn placeholder-error []
  (throw (ex-info "fix placeholders!"
            {:in [:WARNING '[taa.core inputs-outputs-path big-srcs computer-name] :STUBBED-WITH-PLACEHOLDERS]})))

(defn m4-path [demand-name]
  (str inputs-outputs-path "m4_book_" demand-name ".xlsx"))

(defn rc-run-prep [input-map]
  (assoc input-map
         :upper 1.5
         :upper-rc 1.5
         :lower-rc 0
         :min-distance 5
         :include-no-demand false))

(defn rc-runs [input-map filter-big? comp-name demand-name i threads]
  (placeholder-error)
  (capacity/do-taa-runs (m4-path demand-name)
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
  (placeholder-error)
  (let [identifier (:identifier input-map)]
    (doseq [i rep-indices]
      (capacity/do-taa-runs (m4-path identifier)
        (assoc (if rc-runs? (rc-run-prep input-map) input-map)
           ;;not used with :replicator
           ;;:reps num-reps
           :conj-proj {:replicator
                       (partial random/portion-of-reps rep-fraction)}
           :seed (rand Long/MAX_VALUE)
           :identifier (str identifier "_" file-tag "_"
                         i "-" computer-name)
           :threads threads)))))
