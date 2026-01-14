;;This is a port of a recent working example.
;;We generalize where necessary.
(ns taa.batch_test
  (:require [taa [core :as core]
             [capacity :as capacity]
             [requirements :as requirements]
             [demandanalysis :as analysis]]
            [spork.util [io :as io]]))

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

;;NOTE - we can define a helper to avoid having to recompute these.
;;as well as allowing us to interpret inputs like a descending vector of threshold->weight...
(defn assess-risk [x]
  (cond (>= x 0.95) 0
        (>= x 0.9) 1
        (>= x 0.8) 2
        (>= x 0.7) 3
        (>= x 0)   4))

(def weights
  {"comp1" 0.25
   "phase2" 0.125
   "phase3" 0.5
   "phase4" 0.125})

(def curr-supply-demand "notional.xlsx")
;;if we have a pre-baked input, we don't need any of the workbook
;;build stuff.  we just want output stuff.
(def input-map-AP
  {:resources-root inputs-outputs-path
   ;;an excursion name to prepend to output files
   :identifier "AP"
   ;;the name of the timeline file that exists in the resource path
   :timeline-name "timeline_A.xlsx"
   :forge-files forge-AP
   ;;the name of the base marathon file that exists in the resources path.
   :base-m4-name "m4base.xlsx"
   ;;phases for results.txt
   :phases phases-AP
   ;;compo lengths for rand-runs
   :compo-lengths {"AC" 960 "RC" 2010 "NG" 2010}
   ;;usually 30
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
;;parent directory of our inputs, useful for building
;;relative paths later.  this "should" be equivalent
;;to (input-map-AP :resources-root) by convention.
(def root-path (io/parent-path path-AP))

;;Workbook Prep
;;=============

;;We assume the workbooks are already built and can be
;;found at  path-AP or  path-BP.

;;Batch Runs
;;==========
(def node-count 10)
;;emit an optimized run-plan.
(def plan-path "plan.edn")

;;This is a convenience function to emit our run-plan.
;;Feel free to copy or derive from it to point to other stuff.
(defn prep-batches []
  (taa.core/emit-plan
   (io/file-path taa.usage-test/root-path "m4_book_AP.xlsx")
   input-map-AP
   node-count
   :out-path plan-path))

;;Node Execution
;;==============

;;After this point, we have "execution code"
;;We assume a plan exists, and we have an input map.
(def ^:dynamic *plan-path* (io/file-path taa.usage-test/root-path "m4_book_AP.xlsx"))
(def ^:dynamic *input-map* input-map-AP)

;;Ostensibly, both are defined above, or through some variation
;;in the script using the facilities listed.

;;Doing a batch is then ensuring the dynamic vars for *plan-path* and
;;*input-map* are bound, and then looking up the current index from
;;our environment variable.

;;execute the plan on a node.
;;this is, effectively, our CLI.
(defn do-batch [plan-payth idx]
  (core/run-from-plan *plan-path*  idx *input-map*))

;;The default scheme (if the inputs don't exist) would be to
;;generate our run plans on the host machine prior to job submission.
;;Then as part of our job we have:
;; - one or more input workbooks
;; - one or more corresponding input-maps

;;So, we should be able to invoke this from bash pretty easily.
;;e.g., (do (load-file "batch_test.clj")
;;          (taa.batch-test/do-batch $INDEX))
