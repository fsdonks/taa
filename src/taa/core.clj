(ns taa.core
  ;;Require both capacity and requirements here so that the user can
  ;;call functions in either from this init namespace.
  (:require [taa [capacity :as capacity]
             [requirements :as requirements]
             [demandanalysis :as analysis]
             [patch]]
            [spork.util [io :as io]]
            [spork.opt [dumbanneal :as da] [annealing :as ann]]
            [marathon.analysis.random :as random]
            [clojure.data.priority-map :as pm])
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
  (println ['taa.core/variable-rep-runs "DEPRECATED" :prefer 'taa.core/taa-runs])
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

;;one representation is a 2d array of bin x item.
;;sparse is probably better but meh.
;;we want all bins to have similar workloads.
;;so we mix stuff around until we get pretty close to parity.

;;we want a bag that we can pop random jobs from.
;;vector can do it with subvec tricks.
(defn vpop [v idx]
  (let [tail (dec (count v))]
    (if (== idx tail)
      (pop v)
      (-> v
          (assoc idx (nth v tail))
          pop))))

;;uses simulated annealing to find an approximately optimal solution to
;;the multi machine|processor scheduling problem.
;;our goal is to minimize the makespan (total time required to produce then
;;complete result for all jobs).  We do so by simply minimizing the maximum
;;workload for any one node.  Our neigborhood function explores via random
;;swaps between the node with the current max load, and the node with the
;;current minimum load, following a simulated annealing framework.
;;This gets us pretty close to optimal if possible, with decently
;;packed workloads across nodes.  If it's possible, we can find exact
;;solutions immediately with simple round-robin workloads too.

;;The resulting plan is a map of
;;{:root-project path-string|taa-project-map
;; :batches [[{:src "44601P010", :supply {"AC" 0, "RC" 1}, :reps 9, :volume 9} .....] ...]}
;;The idea being that we can load a plan and run a batch fairly simply.
;;We need to specify where to emit results though.
;;If these are all being done on their own node, then we use the normal
;;:threads argument to modify our resource usage.
;;Naively we then just load the project and execute one or more batches, where
;;executing a batch implies doing the src and supply variation experiments
;;defined in the batch definition.  We should be able to use the :identifier
;;key in the input map to dump our batch results partition.
(def default-anneal-options
  {:equilibration 30   :t0 100000000 :tmin 0.0000000000001
   :itermax 1000000000 :decay (ann/geometric-decay 0.95)})

(defn optimized-run-plan
  ([node-count batches]
   (optimized-run-plan node-count batches default-anneal-options))
  ([node-count batches anneal-opts]
   (let [batches (vec batches)
         jobs (->> batches
                   (map-indexed (fn [idx m] (assoc m :batch idx)))
                   (mapcat (fn [{:keys [batch src reps volume] :as m}]
                             (repeat reps {:batch batch
                                           :size (/ volume reps)})))
                   (map-indexed (fn [idx m] (assoc m :job idx)))
                   vec)
         job->size   (->> jobs (mapv :size) vec)
         job-count   (count jobs)
         total-volume (->> batches (map :volume) (reduce +))

         ;;we can keep track of max-bin, and totals.
         ;;so our bins are {:items ... :total k :id n}
         ;;then we have a sorted-set by total and id.
         bin-compare  (fn [l r] ;;intentionally swap for asc.
                        (let [outer (compare (l :total) (r :total))]
                          (if (not (zero? outer))
                            outer
                            (compare (l :id) (r :id)))))
         state (let [label   (let [atm (atom 0)]
                               (fn [_] (let [res @atm]
                                         (swap! atm (fn [x] (mod (inc x) node-count)))
                                         res)))
                     binned-jobs (->> (range job-count)
                                      (group-by label)
                                      (map (fn [[k xs]]
                                             [k
                                              {:id      k
                                               :total    (->> xs (map job->size) (reduce +))
                                               :entries  (vec xs)}]))
                                      (into (pm/priority-map-by (comp - bin-compare))))]
                 {:job-count job-count
                  :bin-count node-count
                  :bins      binned-jobs})
         root-project (->> batches first :root-project)
         un-jobs (fn [{:keys [bins]}]
                   (->> bins
                        vals
                        (map :entries)
                        (mapv (fn [xs]
                                (->> xs
                                     (map jobs)
                                     (group-by :batch)
                                     (reduce-kv (fn blah [acc batch xs]
                                                  (let [{:keys [size] :as r} (first xs)
                                                        n (count xs)]
                                                    (conj acc (-> (dissoc r :batch :job :size)
                                                                  (merge (batches batch))
                                                                  (assoc :reps n
                                                                         :volume (* size n))
                                                                  (dissoc :root-project)))))
                                                []))))
                        (hash-map :root-project root-project :batches)))
         cost-func (fn [sol] (-> sol :bins vals first :total))]
     state
     (if (->> state :bins vals (map :total) (apply =))
       (un-jobs state)
       ;;let's try!
       (let [base-opts {:step-function
                        (fn step [_ {:keys [bins] :as sol}]
                          (let [[from xs] (first bins)
                                [to ys]   (last bins)
                                idx       (-> xs :entries count rand-int)
                                job       (-> xs :entries (nth idx))
                                delta     (job->size job)
                                bnew (-> bins
                                         (assoc from
                                                (-> xs
                                                    (update :entries vpop idx)
                                                    (update :total - delta)))
                                         (assoc to (-> ys
                                                       (update :entries conj job)
                                                       (update :total + delta))))]
                            (assoc sol :bins bnew)))}
             opts (merge  anneal-opts base-opts)]
       (->> (apply da/simple-anneal
                   cost-func
                   state
                    [opts])
            :best-solution
            (merge  {:job->size job->size})
            un-jobs))))))

;;we can just save this as .edn for now.  fine with that.
;;can change to nippy later if it makes sense.
(defn save-run-plan [tgt plan]
  (spit tgt (with-out-str (prn plan))))

;;when we want to run from a pre-existing plan, we just load
;;the plan from a path (using read-string for now),
;;get the project and relevant batch, then execute the batch.
;;executing a batch means we want to change project->experiments
;;to only load a specific set of designs.
;;So if we have a batch of designs in the form of
;;{:src "55633K100", :supply {"AC" 0, "RC" 27}, :reps 2, :volume 54}
;;{:src "55633K100", :supply {"AC" 1, "RC" 27}, :reps 2, :volume 54}


;;e.g., we will invoke this from a script where our input map is defined.
(defn run-from-plan [plan-path batch-index input-map]
  (let [{:keys [root-project batches]}
          (clojure.edn/read-string plan-path)
        batch (batches batch-index)
        batch-id  (str "batch_" batch-index)
        _ (println [:emitting-batch batch-index
                    :from plan-path
                    :to (str batch-id "_results.txt")])]
    (capacity/batch-taa-runs root-project batch (assoc input-map :identifier batch-id))))

;;testing run plan junk
(comment
  (def res (taa.core/taa-run-plan
            (io/file-path taa.usage-test/root-path "m4_book_AP.xlsx")
            taa.usage-test/input-map-AP ))
  
  (def opt-plan 
    (->> (optimized-run-plan 11 res
                             {:equilibration 30   :t0 100000000 :tmin 0.0000000000001
                              :itermax 1000000000 :decay (ann/geometric-decay 0.95)})
#_         :batches
#_
         (mapv (fn [xs] (->> xs (map :volume) (reduce +)))))))
