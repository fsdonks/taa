;;This is a port of a recent working example.
;;We generalize where necessary.
(ns taa.batch_test
  (:require [taa [core :as core]
             [capacity :as capacity]
             [requirements :as requirements]
             [demandanalysis :as analysis]]
            [spork.util [io :as io]]
            [clojure.pprint :as pprint]))

;;Here we define effectively a 1:1 port of the first half of our
;;example from taa.usage-test (note useage_test and usage-test are
;;synonymous for path loading purposes).

;;The intent is to have one script that we can
;;run to build our taa inputs and optionally derive a run-plan,
;;or optionally execute an indexed batch from an existing run-plan.

;;Expectations:

;;- We already have an m4book[].xlsx that we want to perform runs on.
;;  This is a "baked" marathon workbook build that we built using the normal
;;  TAA pipeline build steps.

;;- We have an input-map with the necessary entries, specifically the
;;  subset of keys that we care about for our run distribution tasks.
;;  E.g.,
;; :lower :upper :lower-rc :upper-rc :min-distance not used.

;;[:identifier
;; :resources-root
;; :reps
;; :phases
;; :compo-lengths
;; :merge-rc?
;; :bin-forward?
;; :forward-names
;; :threads
;; :include-no-demand ]

;;This is the root path to our project.
;;tbd, change to resolve res.
(def inputs-outputs-path (io/file-path "./test/resources/usage"))
(def src-str-branch  (io/file-path inputs-outputs-path "notional.xlsx"))

(def forward-names-ac #{"Forward Stationing"})
(def forward-names-rc #{"CampForwardRC"})
(def forward-map {:ac forward-names-ac
                  :rc forward-names-rc})
(def forward-names (capacity/fwd-demands forward-map))

;;Phase definitions
(def phases-AP
  [["comp1" 1 1334]
   ["phase1" 1335 1380]
   ["phase2" 1381 1425]
   ["phase3" 1426 1535]
   ["phase4" 1536 2315]
   ["comp2"  2316 3649]])

(def phases-BP
  [["comp1" 1 1334]
   ["phase1" 1335 1340]
   ["phase2" 1341 1345]
   ["phase3" 1346 1435]
   ["phase4" 1436 2315]
   ["comp2"  2316 3649]])

(def curr-supply-demand "notional.xlsx")
;;if we have a pre-baked input, we don't need any of the workbook
;;build stuff.  we just want output stuff.
(def input-map-AP
  {:resources-root inputs-outputs-path
   ;;an excursion name to prepend to output files
   :identifier "AP"
   ;;the name of the base marathon file that exists in the resources path.
   :base-m4-name "m4base.xlsx"
   ;;phases for results.txt
   :phases phases-AP
   ;;compo lengths for rand-runs
   :compo-lengths {"AC" 960 "RC" 2010 "NG" 2010}
   ;;usually 30, although we'll have variable reps implicitly for the taa invocation.
   :reps 30
   ;;low bound on AC reductions, usually 0 (no AC inventory)
   :lower 0
   ;;Max AC growth.  Usually 1 (base inventory).
   :upper 1
   :upper-rc 1
   :lower-rc 1
   :min-distance 0
   ;;24 for TAA runs on our 2 fastest computers, 20 for other 2
   :threads (marathon.analysis.util/guess-physical-cores)
   ;;include no demand should be false unless we are doing the 1-n RA runs.
   :include-no-demand true
   :bin-forward? true
   :forward-names forward-map
   :merge-rc? true})

;;Parameters are the same as AP since policies and 4a timings are the same.
;;policy-map-name and default-rc policy are also the same as AP for the same
;;reasons.

(def input-map-BP
  (assoc input-map-AP
         :phases phases-BP
         :identifier "BP"))

;;m4-path says :
;;lookup the path for the workbook relative to the input-map's resources-root
;;with a baked name, like /{resources-root ... }/m4book_{demand-name}

(def path-AP (core/m4-path input-map-AP "AP"))
(def path-BP (core/m4-path input-map-BP "BP"))
(def root-path (io/parent-path path-AP))

;;Workbook Prep
;;=============

;;We assume the workbooks are already built and can be
;;found at  path-AP or  path-BP.

;;Batch Runs
;;==========
(def default-node-count 10)
;;emit an optimized run-plan.
(def plan-path "plan.edn")

(def default-plans
  {"AP" {:path path-AP :input-map input-map-AP :plan-path "planAP.edn"}
   "BP" {:path path-BP :input-map input-map-BP :plan-path "planBP.edn"}})

;;This is a convenience function to emit our run-plan.
;;Feel free to copy or derive from it to point to other stuff.
(defn prep-batches
  ([] (prep-batches default-plans))
  ([plans]
   (pprint/pprint [:emitting-batches plans])
   (doseq [[id {:keys [path input-map plan-path node-count]
                :or {node-count default-node-count}}] plans]
     (taa.core/emit-plan
      path-AP
      input-map-AP
      node-count
      :out-path plan-path))))

;;Node Execution
;;==============

;;After this point, we have "execution code"
;;We assume a plan exists, and we have an input map.

;;Ostensibly, both are defined above, or through some variation
;;in the script using the facilities listed.

;;Doing a batch is then ensuring the dynamic vars for *plan-path* and
;;*input-map* are bound, and then looking up the current index from
;;our environment variable.

;;execute the plan on a node.
;;this is, effectively, our CLI.

;;(taa.core/run-from-plan plan-path idx input-map)

;;The default scheme (if the inputs don't exist) would be to
;;generate our run plans on the host machine prior to job submission.
;;Then as part of our job we have:
;; - one or more input workbooks
;; - one or more corresponding input-maps

;;So, we should be able to invoke this from bash pretty easily.
;;see batch_script.clj for an example, and batch.pbs for the calling script.

;;Mocking
;;=======

;;for a simplified mock test, we can do the first item from each node
;;to simulate batching across 3 nodes and make sure the plumbing is running.

#_
(->> default-plans
     (reduce-kv (fn [acc k v] (assoc acc k (assoc v :node-count 3))) default-plans)
     prep-batches)

#_
(doseq [plan-path (->> default-plans vals (map :plan-path))]
  (let [{:keys [batches] :as plan} (taa.core/load-plan plan-path)
        new-batches (->> batches
                         (mapv (fn [xs]
                                 (let [{:keys [reps volume] :as r} (first xs)]
                                   [r #_(assoc r :reps 1
                                           :volume  (/ volume reps))]))))
        new-path  (str "small-" plan-path)]
    (println [:emitting :tiny-plan new-path])
    (spit new-path (assoc plan :batches new-batches) )))

;;run our small plans...
;;these will get dumped into results_batch_k.txt for each
;;batch run.
#_(doseq [i (range 3)]
    (taa.core/run-from-plan "small-planAP.edn" i (assoc input-map-AP :run-site :debug)))

;;it's an exercise for the reader to collate the batch results
;;ex post facto.

;;they are TSV files with identical fields, so it's simple enough
;;to concat them from bash or through clojure pretty trivially.
