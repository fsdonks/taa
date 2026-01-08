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
;;We default to variable rep runs (using our heuristic defined in
;;taa.core/rep-count).  If a caller wants to have FIXED reps, they
;;have to opt-in by either changing :project->reps to (constantly k)
;;where k is an integer rep count, or have an integer :reps entry in the input-map
;;and supply (taa-runs ... :project->reps nil ...) for the taa-runs invocation
;;which will fall back to the rep count setup in the input map.
(defn taa-runs [wkbk-path input-map &
                {:keys [project->reps rc-runs? threads] :as opts
                 :or {threads (marathon.analysis.util/guess-physical-cores)
                      project->reps project->variable-reps}}]
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

;;we'd like to estimate run volume and provide some high-level means for
;;partitioning work by volume.

;;We can "typically" correlate the amount of supply in a run with it's total
;;effort.  Then multiply by reps and we get run volume for an SRC.

;;we use taa.capacity/taa-dry-run to get a seq of maps that provides our
;;designs + rep counts per design.  Since this is all downstream of the
;;normal API, we should be able to get the same results given the same
;;inputs.

(defn taa-run-plan [wkbk-path input-map &
                    {:keys [project->reps rc-runs? threads] :as opts
                     :or {threads (marathon.analysis.util/guess-physical-cores)
                          project->reps project->variable-reps}}]
  (capacity/taa-dry-run
   wkbk-path
   (assoc (if rc-runs? (rc-run-prep input-map) input-map) ;;this is just supplementary junk IMO.
          ;;not used with :replicator
          ;;:reps num-reps
          :conj-proj {:replicator  project->reps}
          :seed       (rand Long/MAX_VALUE)
          :threads threads)))

;;we'd like to split our runs into equal partitions so each
;;node gets the same batch of work.  since we know
;;apriori, we can do this.  Then for system like e.g., PBS,
;;it's pretty trivial to load a run plan and index into a specific
;;batch or pre-cooked file with the experiments to run + reps.
;;e.g. if we have 500 volume and 55 nodes, we can distribute
;;that in batches of 9 pretty evenly.
;;I think we can just make the part-size (inc (quot volume node-count)).
;;We could also just try to pack naively....could also sort by volume,
;;then just round-robin distribute the work.
(defn partitioned-run-plan [node-count xs]
  (->> xs
       (sort-by (comp - :volume))
       (map (fn [nd wrk] (assoc wrk :node nd))
            (cycle (vec (range node-count))))))

(require '[spork.opt.dumbanneal :as ann])

;;one representation is a 2d array of bin x item.
;;sparse is probably better but meh.
;;we want all bins to have similar workloads.
;;so we mix stuff around until we get pretty close to parity.
#_
{:deviation    "total deviation between bins, e.g | bin1V - bin2V - bin3V|"
 :volumes   [] ;;total volume of each bin
 :jobs      [] ;;assignment of rep->bin.
 :total-volume  1}

(defn ->var [init] (atom init))
(defn ->vars [inits] (->> inits (mapv atom)))
(defn ->sum [xs]
  (let [res (atom (->> xs (map deref) (map (fn [x] (or x 0))) (reduce +)))
        _ (doseq [x xs]
            (add-watch x (gensym "sum")
                       (fn [_ _ vold vnew]
                         (when-not (== vold vnew)
                           (let [delta (- vnew vold)]
                             (swap! res + delta))))))]
    res))

(defn ->avg [xs]
  (let [n     (count xs)
        total (->sum xs)
        res   (atom (/ @total n))
        _ (add-watch total (gensym "avg")
                     (fn [_ _ vold vnew]
                       (when-not (== vold vnew)
                         (reset! res (/ vnew n)))))]
    res))

(defn ->fn1 [f x]
  (let [atom? (instance? clojure.lang.IDeref x)
        x (if atom?
            x
            (atom x))
        res (atom (f @x))
        _   (when atom?
              (add-watch x (gensym "fn1") (fn [_ _ vold vnew]
                                            (when (not= vold vnew)
                                              (reset! res (f vnew))))))]
    res))

(defn ->fn [f xs]
  (if-not (coll? xs)
    (->fn1 f xs)
  (let [dirty (atom false)
        ^java.util.ArrayList
        cache (java.util.ArrayList. (for [x xs] (if (instance? clojure.lang.IDeref x) @x x)))
        init-val (apply f cache)
        current (atom init-val)
        eval! (fn []
                (if @dirty
                  (do (println cache)
                      (reset! dirty nil)
                      (reset! current (apply f cache)))
                  @current))
        _ (doseq [[idx atm] (map-indexed vector xs)]
            (when (instance? clojure.lang.IDeref atm)
              (add-watch atm (gensym "fn-arg")
                         (fn [_ _ vold vnew]
                           (when-not (= vold vnew)
                             (.set cache idx vnew)
                             (reset! dirty true)
                             )))))]
    (reify clojure.lang.IRef
;	    void setValidator(IFn vf)
;      IFn getValidator()
;      IPersistentMap getWatches()
      (addWatch [this k f]
        (add-watch current k f))
      (removeWatch  [this k]
        (remove-watch current k))
      (deref [this] (eval!))))))

(defn ->map1 [f xs]
  (mapv (fn [atm] (->fn f atm)) xs))

;;try to pack our reps into equivalent batches.
(defn apack [node-count batches]
  (let [batches (vec batches)
        jobs (->> batches
                  (map-indexed (fn [idx m] (assoc m :batch idx)))
                  (mapcat (fn [{:keys [batch src reps volume] :as m}]
                            (repeat reps {:batch batch
                                          :size (/ volume reps)})))
                  (map-indexed (fn [idx m] (assoc m :job idx)))
                  vec)
        total-volume (->> batches (map :volume) (reduce +))
        ;;ideal bin size.
        theoretical  (/ total-volume (double node-count))
        ^longs
        job->size   (->> jobs (mapv :size) long-array)
        job-count   (count jobs)
        <totals>    (->vars (repeat  node-count 0))
        <devs>      (->map1 (fn [tot] (Math/abs (- tot theoretical))) <totals>)
        <avg-dev>   (->avg <devs>)
        push-bin!  (fn push-bin! [{:keys [bins totals]} ^long bidx ^long job]
                    (doto ^java.util.HashSet (bins bidx)  (.add job))
                    (swap!  (<totals> bidx) + (aget job->size job)))
        pop-bin!  (fn pop-bin! [{:keys [bins totals]} ^long bidx ^long job]
                    (doto ^java.util.HashSet (bins bidx)
                      (.remove job))
                    (swap!  (<totals> bidx) - (aget job->size job)))
        state (let [inits (vec (repeatedly node-count #(java.util.HashSet.)))
                    init-state {:job-count job-count
                                :theoretical theoretical
                                :bins      inits
                                :totals   <totals>
                                :devs     <devs>
                                :avg-dev  <avg-dev>}]
                     (doseq [[bin job] (map vector
                                            (cycle (range node-count))
                                            (range job-count))]
                       (push-bin! init-state bin job))
                     init-state)
        un-jobs (fn [{:keys [bins]}]
                  (->> bins
                       (mapv (fn [xs]
                               (->> xs
                                    (map jobs)
                                    (group-by :batch)
                                    (reduce-kv (fn blah [acc batch xs]
                                                 (let [{:keys [size] :as r} (first xs)
                                                       n (count xs)]
                                                   (conj acc (-> (dissoc r :batch :job)
                                                                 (merge (batches batch))
                                                                 (assoc :reps n
                                                                        :volume (* size n))))))
                                               []))))))]
    (if (-> state :avg-dev deref zero?)
      (un-jobs state)
      ;;let's try!
    state)))
