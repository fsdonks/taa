;;This is a port of a recent working example.
;;We generalize where necessary.
(ns taa.batch_test
  (:require [taa [core :as core]
                 [capacity :as capacity]
                 [requirements :as requirements]
                 [demandanalysis :as analysis]]
            [taapost [shave :as shave]
                     [bcd   :as bcd]
                     [nlist :as nlist]
                     [util  :as u]]
            [marathon.analysis.random :as random]
            [demand_builder.forgeformatter :as ff]
            [spork.util [io :as io]]
            [tablecloth.api :as tc]
            [oz.core :as oz]
            [spork.util.excel.core :as xl]))

;;Here we define effectively a 1:1 port of the first half of our
;;example from taa.usage-test (note useage_test and usage-test are
;;synonymous for path loading purposes).

;;The intent is to have one script that we can
;;run to build our taa inputs and optionally derive a run-plan,
;;or optionally execute an indexed batch from an existing run-plan.

;;This is the root path to our project.
;;tbd, change to resolve res.
(def inputs-outputs-path (io/file-path "./test/resources/usage"))
(def src-str-branch  (io/file-path inputs-outputs-path "notional.xlsx"))

(def forward-names-ac #{"Forward Stationing"})
(def forward-names-rc #{"CampForwardRC"})
(def forward-map {:ac forward-names-ac
                  :rc forward-names-rc})
(def forward-names (capacity/fwd-demands forward-map))
(def vignettes (clojure.set/union #{"Rotational"
                                    "IRF"
                                    "CRF"}
                                  forward-names))

(def vignettes-AP (conj vignettes "Alpha_Residual"))
(def vignettes-BP (conj vignettes "Beta_Residual"))

;;currently expects a worbook with a sigle spreadsheet.
;;multisheet books aren't supported (could be).
;;I'm pretts sure the values are path-relative to project root.
(def forge-AP {"A" "FORGE_A.xlsx"})
(def forge-BP {"B" "FORGE_B.xlsx"})

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

(def big-srcs
  #{"10557KC00"
    "27523KC00"
    "19463P200"
    "09547KB10"
    "10557KB00"
    "55508KA00"
    "41750K000"})

(def curr-supply-demand "notional.xlsx")

(def input-map-AP
  {:resources-root inputs-outputs-path
   ;;what usage.clj should be specifying:
   ;;path to SupplyDemand (also has a policy_map worksheet)
   ;; no matching clause exceptions might be for N/As in excel formulas.
   ;; when reading workbooks into clojure.  Change these values to
   ;; something else like "na".
   ;;Will also get an error when some values for idaho and RC available
   ;;aren't numbers.... turn stuff to 0's or delete.
   ;;make sure strength in SRC_By_Day has numbers! if not, set to 0
   ;;Assume SRC_By_Day is in SupplyDemand
   ;;The name of the supply demand file that exists in the
   ;;resources-root folder
   :supp-demand-name curr-supply-demand
   ;;Same 4a end day for both legacy demands!
   :policy-map-name "policy_mappings.xlsx"
   ;;a set of vignettes to keep from SupplyDemand (ensure SupplyDemand
   ;;has RCAvailable and HomelandDefense)
   :vignettes vignettes-AP
   ;;a default RC policy name
   :default-rc-policy "TAA22-26_RCSurge_Default_Composite_2529_AP"
   ;;a post process function with priority, category, and sourcefirst
   ;;post process demand records to copy vignette and DemandGroup and
   ;;set priorities
   ;;Change Forward stationed category to NonBOG, SourceFirst to NOT-RC-MIN
   ;;PTDOS are regular {category = Rotational, SourceFirst = NOT-RC},
   ;;Idaho-Competitoin is category = NonBOG and SourceFirst = NOT-AC-MIN
   ;;Idaho is category = NonBOG adn SourceFirst = NOT-AC-MIN
   :set-demand-params
   (fn [{:keys [Vignette Category SourceFirst Operation SRC] :as r}]
     (assoc r
            :DemandGroup (if (clojure.string/starts-with? Operation "PH IV")
                               (str Vignette "-PH IV")
                              Vignette)
            :Priority (condp = Vignette
                        ;;intense stuff
                        "SE-A" 4 ;P or D
                        "SE-B" 4
                        "Alpha_Residual" 3
                        "Beta_Residual" 3
                        "HomelandDefense" 2
                        "CRE" 2
                        ;;comp stuff
                        "Forward Stationing" 1
                        ;;cannibalization
                        "RC_NonBOG-War" 1
                        ;;"CampForwardRC" 2
                        "Rotational" 5
                        ;;after fowrard and before rotational
                        "IRF" 3
                        "CRF" 4)
            :Category (if (clojure.string/includes? Vignette "Forward")
                        "Forward" Category)
            :SourceFirst (case Vignette
                           "Alpha_Residual" "NOT-RC-MIN"
                           "Beta_Residual"  "NOT-RC-MIN"
                           "Forward Stationing" "NOT-RC"
                           ;;"CampForwardRC" "NOT-AC"
                           "IRF" "NOT-RC"
                           "CRF" "NOT-RC"
                           SourceFirst
                           )
            :Tags (if (contains? forward-names Vignette)
                     (str {:region :forward})
                     "")))
   ;;an excursion name to prepend to output files
   :identifier "AP"
   ;;the name of the timeline file that exists in the resource path
   :timeline-name "timeline_A.xlsx"
   :forge-files forge-AP
   ;;the name of the base marathon file that exists in the resources path.
   :base-m4-name "m4base.xlsx"
   ;;phases for results.txt
   :phases phases-AP
   ;;need to change these manually right now for the cannibal and idaho
   ;;records.  This is okay, highlights the fact that there is a separate
   ;;pipeline for these just in case we want to go back to assuming cannibalized
   ;;units can fill idaho.  For the most recent TAA, we are currently assuming
   ;;that cannibalized units can't be used for anything!
   ;;this will adjust for either scenario.
   :cannibal-start "phase1"
   :cannibal-end   "phase3"
   :idaho-start    "phase1"
   :idaho-end      "phase3"
   :idaho-name     "HomelandDefense"
   :periods-name   "periods_AP.xlsx"
   :parameters-name "parameters_AP.xlsx"
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
   :threads 24
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
         :timeline-name "timeline_B.xlsx"
         :phases phases-BP
         :vignettes vignettes-BP
         :identifier "BP"
         :forge-files forge-BP
         :periods-name "periods_BP.xlsx"))

;;rc will get tacked on for rc runs
(def demand-names ["AP" "BP"])
(def path-AP (core/m4-path input-map-AP "AP"))
(def path-BP (core/m4-path input-map-BP "BP"))
;;parent directory of our inputs, useful for building
;;relative paths later.  this "should" be equivalent
;;to (input-map-AP :resources-root) by convention.
(def root-path (io/parent-path path-AP))


;;Workbook Prep
;;=============
(defn build-them []
  (binding [capacity/*default-rc-ratio* 0.5] (capacity/preprocess-taa input-map-AP))
  (binding [capacity/*default-rc-ratio* 0.5] (capacity/preprocess-taa input-map-BP)))

;;Batch Runs
;;==========
(def node-count 10)
;;emit an optimized run-plan.
(def plan-path "plan.edn")

(defn emit-plan [in-path input-map]
  (let [res (->> (taa.core/taa-run-plan in-path input-map)
                 (taa.core/optimized-run-plan node-count))
        _    (println [:spitting :run-plan :to "plan.edn"])
        _    (core/save-run-plan plan-path res)]
    res))

(defn prep-batches []
  (emit-plan (io/file-path taa.usage-test/root-path "m4_book_AP.xlsx")
             input-map-AP))

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

;;So, we should be able to invoke this from bash pretty easily.
;;e.g., (do (load-file "batch_test.clj")
;;          (taa.batch-test/do-batch $INDEX))

(defn do-batch [idx]
  (core/run-from-plan *plan-path*  idx *plan-map*))

;;note: we have some other options too.  We can choose to
;;break up the management with separate scripts, then have our
;;main invocation eval a single script that loads those in turn.
