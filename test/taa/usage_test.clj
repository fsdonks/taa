;;This is a port of a recent working example.
;;We generalize where necessary.
(ns taa.usage-test
  (:require [taa [core :as core]
                 [capacity :as capacity]
                 [requirements :as requirements]
                 [demandanalysis :as analysis]]
            [taapost [shave :as shave]
                     [bcd   :as bcd]
                     [nlist :as nlist]]
            [marathon.analysis.random :as random]
            [demand_builder.forgeformatter :as ff]
            [spork.util [io :as io]]))

;;note - original script assumed in-ns taa.core, so we are
;;slightly complicating this but meh.

;;This is the root path to our project.
;;tbd, change to resolve res.
(def inputs-outputs-path (io/file-path "./test/resources/usage"))
(def src-str-branch  (io/file-path inputs-outputs-path "notional.xlsx"))

;;How to prep input data:
;;modifications to SupplyDemand
;;Unhide Columns and delete the hidden columns because they include N/As
;;You won't be able to load an Excel workbook that includes N/As
;;Copy and paste everything as values!
;;Search and replce #N/A values to blanks
;;Delete top rows before table headers.
;;Replace newlines in the headers search for Ctrl+J and replace
;;with a blank string.
;;Rename SupplyDemand worksheet in SupplyDemand workbook accordingly.
;;Make Sure OITitle column is called UNTDS
;;Make sure RCAvailable is named as such OR calculate 50% RC Availability
;;(no rounding preferred) or join with variable RC.

;;Delete all worksheets except for SupplyDemand
;;Rename Strength to STR
;;Delete Camp Forward RC for 27-31 per ferguson

;;FORGE Files:
;;Delete all worksheets except SRC_By_Day in FORGE files.
;;merge Airborne IBCT into leg IBCT
;;Make sure all Strength values aren't blank (set to 0 if necessary)

;;Those are all of the steps needed to prep the input data.
;;________________________________________________________

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

;;builds workbook.
(defn build-them []
  (binding [capacity/*default-rc-ratio* 0.5] (capacity/preprocess-taa input-map-AP))
  (binding [capacity/*default-rc-ratio* 0.5] (capacity/preprocess-taa input-map-BP)))

;;does a single rep of capacity analysis
#_#_
(core/time-s (marathon.core/capacity-analysis path-AP))
(core/time-s (marathon.core/capacity-analysis path-BP))

;;Do only the RA variation with fixed rc for the 1-n list.
;;We are now doing variable reps according to the ra+rc inventory.
;;20 indicates the number of logical processors which shold match
;;what you have for you logical processors in task manager.
#_#_
(core/time-s (core/variable-rep-runs path-AP input-map-AP 1 (range 1) "ra" 24 false))
(core/time-s (core/variable-rep-runs path-BP input-map-BP 1 (range 1) "ra" 24 false))

;;if you only want to run some SRCS for the 1-n list, do this:
#_
(def src-fixes
  #{"44335K000"
    "10633P000"})

;;redo ra supply variation for 1-n runs
#_
(core/time-s
 (core/variable-rep-runs path-AP
    (assoc input-map-AP
           :transform-proj (capacity/supply-src-filter src-fixes true))
    1
    (range 1)
    "ra-rc50_updates"
    24
    ;;false for no, don't do ra*rc
    false))
#_
(core/time-s
 (core/variable-rep-runs path-BP
                         (assoc input-map-BP
                                :transform-proj(capacity/supply-src-filter src-fixes true))
                         1
                         (range 1)
                         "ra-rc50_updates"
                         24
                         ;;false for no, don't do ra*rc
                         false))

;;use the new api to do a quick 1-rep run.
;;currently dumps output to ./random-out.txt
(defn quick-run []
  (core/taa-runs  path-AP input-map-AP
                  :project->reps (constantly  1)))


;;add taa post processing
;; - marathon performance data (bcd)
;; - 1-n
;; - shave charts
;; - tsunami charts

(comment
;;This code is used to spit out results for each day where
;;we treat each day as an individual phase.
(defn daily-phases [[phase start end]]
  (for [day (range start (inc end))]
    [(str phase "-day_" day) day day]))

(def input-map-daily-comp1
  (assoc input-map-AP
         :phases (daily-phases (first phases-AP))
         :identifier "daily-comp1"
         :transform-proj
           (capacity/supply-src-filter #{"06455K100"} true)))

(def input-map-daily-phases-1-3-engbn
  (assoc input-map-AP
         :phases (daily-phases ["phases_1-3" 1335 1535])
         :identifier "daily-phases_1-3-engbn"
         :transform-proj
         (capacity/supply-src-filter #{"05435P200"} true)))

;;this code is for doing tsunami charts (OBE, see new version).


)

;;ex. automated version of Phase definitions, could be useful for other
;;usage scripts.
(comment
  (defn transform [start stop xs]
    (let [shifted  (->> xs
                        (mapv (fn [[k l r]] [k (+ l start) (+ r start)])))]
      (update shifted (dec (count shifted)) assoc 2 stop)))

  (def phases-AP
    (concat [["comp1" 1 1334]]
            (transform 1334 2315
                       (ff/processed-phases-from
                        (io/file-path inputs-outputs-path
                                      (-> forge-AP vals first))))
            [["comp2" 2316 3649]]))
  (def phases-BP
    (concat [["comp1" 1 1334]]
            (transform 1334 2315
                       (ff/processed-phases-from
                        (io/file-path inputs-outputs-path
                                      (-> forge-BP vals first))))
            [["comp2" 2316 3649]])))


(comment ;;wip for exploring projects.
  (require '[portal.api :as p])
  (def p (p/open {:app false})) ;;use the system browser
  (add-tap #'p/submit)
  )
