(ns taa.core
  ;;Require both capacity and requirements here so that the user can
  ;;call functions in either from this init namespace.
  (:require [taa [capacity :as capacity] 
             [requirements :as requirements]
             [demandanalysis :as analysis]]
            [spork.util [io :as io]]
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

(defn m4-path [input-map demand-name]
  (io/file-path (:resources-root input-map) (str "m4_book_" demand-name ".xlsx")))

(defn rc-run-prep [input-map]
  (assoc input-map
         :upper 1.5
         :upper-rc 1.5
         :lower-rc 0
         :min-distance 5
         :include-no-demand false))

;;big-srcs is a small set of SRCs that we want to do a separate set of runs
;;for, so that we can get the smaller inventory SRCs done first.
(defn rc-runs [big-srcs input-map filter-big? comp-name demand-name i threads]
  (capacity/do-taa-runs
   (m4-path input-map demand-name)

   (assoc (rc-run-prep input-map)
          :transform-proj
          (capacity/supply-src-filter big-srcs filter-big?)
          :reps 1
          :seed (rand Long/MAX_VALUE)
          :identifier (str demand-name "_" (if filter-big? "bigs" "smalls")
                           "-" comp-name "-" i)
          :threads threads)))

(defn ra-runs [book-path input-map threads]
  (capacity/do-taa-runs book-path
                        (assoc input-map
                               :upper 1
                               :lower 0
                               :upper-rc 1
                               :lower-rc 1
                               :min-distance 0
                               :threads threads)))

(defn base-only-run-prep [{:keys [identifier] :as input-map}]
  (assoc input-map
         :identifier (str identifier "-base")
         :upper 1
         :lower 1
         :upper-rc 1
         :lower-rc 1
         :min-distance 0))

(defn base-only-runs [book-path {:keys [identifier] :as input-map} threads]
  (capacity/do-taa-runs book-path
                        (assoc (base-only-run-prep input-map)
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
(defn variable-rep-runs
  [wkbk-path input-map rep-fraction rep-indices file-tag
                         threads rc-runs?]
  (let [identifier (:identifier input-map)
        ;;Used to write output files named by the specific computer.
        computer-name (.getHostName (InetAddress/getLocalHost))]
    (doseq [i rep-indices]
      (capacity/do-taa-runs
       wkbk-path

       (assoc (if rc-runs? (rc-run-prep input-map) input-map)
              ;;not used with :replicator
              ;;:reps num-reps
              :conj-proj {:replicator
                          (partial random/portion-of-reps rep-fraction)}
              :seed (rand Long/MAX_VALUE)
              :identifier (str identifier "_" file-tag "_"
                               i "-" computer-name)
              :threads threads)))))



;;Number of reps are based on Sarah's rep analysis
;;copied here for explicitness.
(defn rep-count [ra+rc] ;;
  (cond                 ;;
    (> ra+rc 100) 10    ;;
    (> ra+rc 46) 20     ;;
    (> ra+rc 12) 30     ;;
    (> ra+rc 5) 80      ;;
    (> ra+rc 0) 100     ;;
    (zero? ra+rc) 1))   ;;

;;this is pulled from marathon.analysis.random and refactored forg
;;explicitness, instead of having it stowed away elsewhere.
(defn project->variable-reps [proj]
  (->> (random/total-supply proj)
       (rep-count)))

;;This is a consolidated API function that cleans up the vagaries of doing
;;variable rep runs, constant rep runs, sparse runs, etc.
;;We want to clean up the current split between legacy/deprecated functions.
;;We had taa.capacity/do-taa-runs, then craig wrapped it to enable partial/fractional
;;runs while simultaneously implementing the variable rep strategy from sarah.
;;papers over the minutae of placing replicator function in the right place,
;;optional thread overloads, etc.

;;note: we can control levels of the design a couple of ways.  There's
;;the naive :levels value in the project that gets read.  This is (currently)
;;ignored by taa.capacity/do-taa-runs, since there's no bridge
;;with the project map.  Levels (optionally) provide a quick limit
;;on the number designs we run by limiting the spread to the number
;;of levels and computing variable width steps in between supply
;;changes.  This helps a bit in doing quick test runs, or intentionally
;;sparse designs, but we don't historically leverage it directly
;;in TAA or RUN-AMC.

;;Instead, we leverage a function to emit designs, under
;;marathon.analysis.random/*project->experiments* . This is a function ::
;;project->low->high->[project+] By default, it will be bound to
;;marathon.analysis.random/project->experiments, which won't vary the rc at all.
;;For the taa pipeline, we end up going through
;;marathon.analysis.random/rand-runs-ac-rc, which binds *project->experiments*
;;to a partially applied marathon.analysis.random/project->experiments-ac-rc
;;with rc supply bounds lifted from the project map.

;;From an API perspective, we have a very powerful means to mess with
;;design generation via marathon.analysis.random/*project->experiments*,
;;since this is a deep hook into how a project is expanded into
;;one or more designs (typically some supply variation, but we can do
;;whatever including policy variation, demand, etc.).

;;It would be nice to expose that hook as an option here.
;;We'll stick with the legacy convention - for now - but a good
;;API should be able to allow the user to hook all these things.
(defn taa-runs [wkbk-path input-map &
                {:keys [project->reps rc-runs? threads] :as opts
                 :or {threads (marathon.analysis.util/guess-physical-cores)}}]
  (capacity/do-taa-runs
   wkbk-path
   (assoc (if rc-runs? (rc-run-prep input-map) input-map) ;;this is just supplementary junk IMO.
          ;;not used with :replicator
          ;;:reps num-reps
          :conj-proj {:replicator  project->reps}
          :seed       (rand Long/MAX_VALUE)
          :threads threads)))

;;might be nice to have a quick/debug version of above.
;;also a dry-run or run-plan emitter.
