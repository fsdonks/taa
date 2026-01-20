;;This is a port of a recent working example.
;;We generalize where necessary.
(ns taa.usage-test
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
;;parent directory of our inputs, useful for building
;;relative paths later.  this "should" be equivalent
;;to (input-map-AP :resources-root) by convention.
(def root-path (io/parent-path path-AP))

;;we define case definitions as a map of {case {:keys [path input]}}
;;where the case key is a string identifier that typically matches the
;;identifier key in the input map, path is the path to the m4workbook
;;built earlier, and input is the in-memory input-map for the case.
(def default-cases
  {"AP" {:path path-AP
         :input input-map-AP}
   "BP" {:path path-BP
         :input input-map-BP}})

;;Workbook Prep
;;=============

;;Compiles discrete m4 workbooks with demand, policy, supply
;;settings derived from the input-map and associated distinct input worksheets.
;;This builds a single artifact that can be run through typical
;;capacity analysis (it will include all SRCs by default though).
;;This deterministic artifact will not have random initial conditions; those
;;are determined later in the pipeline when we perform our design of experiment
;;using supply variation, which other aspects of the input-map will parameterize.

;;Note the useage of the *default-rc-ratio*.  This is an optional dynamic variable
;;that we can bind at build-time to define a global default for rc availability
;;assumptions (specifically how much of RC supply is absorbed by a proxy cannibalization
;;demand during conflict).  The current api makes it easy to perform sensistivity
;;analysis on this parameter by varying it and building different inputs that
;;can be fed into the rest of the pipeline.  For now, we just assume 50% if
;;no availability is specifically provided in the input data.
(defn build-case-inputs [case-map]
  (doseq [input-map  (->> case-map vals (map :input))]
    (binding [capacity/*default-rc-ratio* 0.5] ;;let's assume this is wired for now.
      (capacity/preprocess-taa input-map))))

;;Basic Supply Variation Runs
;;===========================

;;We can use the new API to do a (relatively) quick 1-rep run. We use
;;the :project->reps option and supply a function of project->int which
;;indicates how many reps to do for a single design point. This allows a lot of
;;flexbility since we can use ANY function to determine variable reps based on
;;an M4 project map that corresponds to a design point for a supply variation
;;mixture.

;;If we supply nothing explicitly, then the default is to assume the variable
;;rep scheme defined in taa.core/project->variable-reps, which will perform
;;fewer replications for higher supply counts and vice versa.

;;If we want to use a static or fixed rep scheme (like do 30 reps for
;;everything) then we can either provide a function that does this, like
;;:project->reps (constantly k) where k is an integer, or we
;;set our reps in the input-map under :reps, and turn off project->reps by
;;setting it to nil via
;;:project->reps nil
;;on the invocation.

;;little handle we can use to modify the pipeline.
;;useful for testing e.g. quick-reps.
(def ^:dynamic *rep-limit*)

;;currently dumps output to ./random-out.txt
(defn run-cases [case-map & {:keys [rep-limit]}]
  (let [rep-limit (or rep-limit *rep-limit*)
        project->reps (if rep-limit
                        (constantly rep-limit)
                        taa.core/project->variable-reps)]
    (doseq [[case {:keys [path input]}] case-map]
      (println (str "Running " case))
      (core/taa-runs  path input :project->reps project->reps))))

;;Bar Chart Data (BCD)
;;===================

;;builds all BCD info for every results_{tag}.txt file, emits bcd_{cleanedtag}.txt
;;based on the legacy script, this will do an implicit coercion of {tag} where
;;tag = the scenario arg supplied, the resulting cleanedtag will be A, otherwise B.

;;This is currently a weak hardcoding that we probably should work around, but
;;it's consistent with the legacy script.

;;e.g., in the below case,
;;   results_AP.txt -> bcd_AP.txt, and in bcd_AP.txt, the :scenario field will be A
;;   results_BP.txt -> bcd_BP.txt, and in bcd_BP.txt, the :scenario field will be B

;;We do NOT currently cover arbitrary numbers of scenarios for naming, only <= 2.
;;One common use case is to separate multiple experiments (e.g. for sensitivity analysis)
;;into separate folders (e.g., with <= 2 scenarios each).  do-bcds and cat-bcds will
;;crawl from a root folder and compute all the path-relative bcd files as above,
;;with an added field showing the pathname.  Then cat-bcds will do the same - build
;;a concatenated bcd_all.txt from any child file with bcd_ in its prefix.  The final
;;bcd_all.txt will retain the pathname so it's possible to do aggregate analysis or
;;filtering.  One use case would be a root folder containing multiple children, where
;;each child corresponds to a workbook built with a different default-rc-ratio value.
;;Each child then (after performing supply variation runs) with multiple results.
;;The bcd functions will (if supplied with the parent directory), pick up all the children
;;and compute bcd's, and then the cat-bcds will (if supplied with the same parent)
;;concat them all into a file at the root folder.  The caller can trivially alter this
;;by pointing at a different folder structure.  This setup makes it easy to programatically
;;perform bulk experiments and sensitivity analyses, and then collect canonical results
;;for comparison.

;;See taapost.bcd/do-bcds for more information on the implementation.
;;we assume the first case in the case-map is the "base case", which
;;will show generically as "A" in the barchartdata output file, with
;;any other case being "B" (legacy assumption of only 2 cases...)
;;note: in the legacy implementation, we had a separate bcd for the max
;;of the scenarios. we just compute that in memory now from bcd_all.txt
;;effectively.  see taapost.shave/max-by-scenario, which used in
;;taapost.shave/barchart->src-charts, and barchart->phasedata
(defn bcds [case-map]
  (let [[case {:keys [input path]}] (-> case-map first)
        root-path (io/parent-path path)]
    (bcd/do-bcds root-path case)
    ;;Now that we have bcds, we'd like to concat them (this is the format FM expects),
    ;;so we have a simple function that does that:
    (bcd/cat-bcds root-path)))

;;Shave Charts
;;============

;;We now emit shave charts for each scenario.
;;The new shavechart pipeline actually provides the ability
;;to emit based purely on results and a unit_detail workbook,
;;or based on the legacy barchart data (bcd.txt) and a
;;unit_detail workbook.

;;We expect unit_detail to provide a table of [:SRC :TITLE :STR :BRANCH] fields,
;;and it's pretty permissive in how we get that.  Typically, we'll use
;;taapost.shave/read-unit-detail which leverages tableloth, and we point it
;;at a workbook where the first spreadsheet (typically a singleton sheet)
;;has those feeds in normal, contiguous table form.

;;We often have this information already provided from earlier inputs
;;from forge, hence the loose reference to unit_detail.  However, you can
;;use any worksheet that has this information in it, as long as we get
;;an association between SRC TITLE STR BRANCH, we're okay.
;;We might append that information to the base SupplyDemand worksheet and
;;just use that, or we can pull it in from another source.
;;Here we'll use the SRC baseline that we typically have on hand,
;;which should provide all the information out of the box.

;;Since this is typically shared information we'll just keep it global for now.
(def default-unit-detail-path (io/file-path root-path "SRC_STR_BRANCH.xlsx"))
(def default-unit-detail      (shave/read-unit-detail default-unit-detail))

;;Going this route, we only need the results file and a commensurate unit-detail
;;table.  We can interactively render the ph3 branch shave charts pretty easily.
;;Invoking the following fn will pop up a browser window and display all the
;;vega plots for shave charts for each branch.  This is useful for interactive
;;testing and analysis.  As you will see, there are corresponding simplified paths
;;for just emitting the plots (you can also print them as a pdf from the browser window
;;semi-manually, but headless rendering is more desirable for generating results since
;;it requires no visual rendering or web browser).

#_
(defn render-branch-test []
  (let [dt (tc/dataset (io/file-path root-path "results_AP.txt")
                {:key-fn keyword :separator \tab})
        ph3 (shave/phase-data dt default-unit-detail "phase3")]
    (oz/view! (->  ph3 shave/branch-charts))))

;;You can interactively view subsets of the data using tablecloth to filter down
;;the bcd dataset or otherwise transform it prior to supplying to various shave chart operations.
;;As before, if you evaluate these commands they will provide interactive views of branch
;;charts for conflict and competition in the web browser.

;;If we want just emit all the typical shave charts, we can do so:
(defn emit-all-shave-charts
  [root-path bcd-data]
  (let [chartroot    (io/file-path root-path "charts")
        competition  (io/file-path chartroot "/branch/competition")
        conflict     (io/file-path chartroot "/branch/conflict")
        agg          (io/file-path chartroot "branch-agg")]
    (-> bcd-data
        (tc/select-rows (fn [{:keys [phase]}] (= phase "comp1")))
        (shave/emit-branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                   :subtitle "Campaigning"
                                   :root competition}))
    (-> bcd-data
        (tc/select-rows (fn [{:keys [phase]}] (= phase "phase3")))
        (shave/emit-branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                   :subtitle "Conflict-Phase 3 Most Stressful Scenario"
                                   :root conflict}))
    (-> bcd-data
        (tc/select-rows (fn [{:keys [phase]}] (= phase "phase3")))
        (shave/emit-agg-branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                       :subtitle "Conflict-Phase 3 Most Stressful Scenario"
                                       :root agg}))))

;;Note: we can get the same information from bcd.txt output as an alternative.
;;Both lead to the same end-state, but this variant supports rendering legacy
;;results if we need to (as per last year).
(defn shave-test []
  (let [bcd-path     (io/file-path root-path "bcd_AP.txt")
        unit-detail  (shave/read-unit-detail
                      (io/file-path root-path "SRC_STR_BRANCH.xlsx"))]
    (when-not (io/fexists? bcd-path) (bcds))
    (-> (tc/dataset bcd-path {:separator "\t" :key-fn keyword})
        (shave/barchart->src-charts unit-detail))))

;;N-List
;;======
;;These are the weightings we use for nominal phases.
;;They're variable on purpose.
(def default-phase-weights
  {"comp1" 0.1
   "phase1" 0.1
   "phase2" 0.1
   "phase3" 0.25
   "phase4" 0.1
   "comp2" 0.1})

;;we use an nlist-spec to define how to label our stuff in the nlist
;;ultimately.  we encode multiple cases, e.g. A B etc., and for
;;each case there are local paths for the results and the input
;;m4book, e.g. results_ap.txt and m4_book_AP.txt
;;global parameters are merged in outside of the cases entry.
(def default-nlist-spec
  {:cases {"A" {:results (io/file-path root-path "results_AP.txt")
                :m4book  (io/file-path root-path "m4_book_AP.xlsx")}
           "B" {:results (io/file-path root-path "results_BP.txt")
                :m4book  (io/file-path root-path "m4_book_AP.xlsx")}}
   :phase-weights default-phase-weights
   :unit-detail  (io/file-path root-path "SRC_STR_BRANCH.xlsx")})

(defn map-vals [f m]
  (reduce-kv (fn [acc k v] (assoc acc k (f v))) m m))

;;helper function to unpack our nlist spec into the legacy
;;format make-one-n expects.
(defn unspec [{:keys [cases] :as spec}]
  (-> spec
      (dissoc :cases)
      (assoc :results (map-vals :results cases)
             :m4books (map-vals :m4book cases))))

(defn spit-nlist [nlist-spec]
  (let [{:keys [results m4books unit-detail phase-weights]} (unspec nlist-spec)
        res   (nlist/make-one-n results m4books "."
                                phase-weights "one_n"
                                unit-detail {})]
    (->> (-> res nlist/simple-names  (tc/rename-columns (fn [k] (name k))))
         (xl/table->xlsx "res.xlsx" "results"))))

;;the complete all-in-one pipeline.
;;this composes all our prior functions to:
;;  for each case,
;;    build the taa inputs (m4book and friends),
;;    execute runs to get supply variation results
;;    generate unified bar chart data from results
;;    generate shave-charts for both cases from barchart data
;;    generate n-list workbook for both scenarios.

(defn taa-pipeline
  ([] (taa-pipeline default-case-map default-nlist-spec default-unit-detail-path))
  ([case-map nlist-spec unit-detail]
   (build-case-inputs case-map) ;;build m4workbooks for each case.
   (run-cases case-map)
   (bcds case-map)
   (let [bcd-path    (io/file-path root-path (->> case-map keys first "bcd_AP.txt"))
         unit-detail (shave/read-unit-detail unit-detail)
         bcd      (-> (tc/dataset bcd-path {:separator "\t" :key-fn keyword})
                      (shave/barchart->src-charts unit-detail))]
     (emit-all-shave-charts :bcd-data bcd))
   (spit-nlist nlist-spec)))

;;some other visualization API examples.
#_
(oz/view! (-> bcd2
              (tc/select-rows (fn [{:keys [phase]}] (= phase "phase3")))
              (shave/branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                    :subtitle "Conflict-Phase 3 Most Stressful Scenario"})))

#_
(oz/view! (-> bcd2
              (tc/select-rows (fn [{:keys [phase]}] (= phase "comp1")))
              (shave/branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                    :subtitle "Campaigning"})))

#_
(oz/view! (-> bcd2
              (shave/agg-branch-charts {:title "Aggregated Modeling Results as Percentages of Demand"
                                        :subtitle "Most Stressful Scenario By Branch"})))

;;does a single rep of capacity analysis
#_#_
(core/time-s (marathon.core/capacity-analysis path-AP))
(core/time-s (marathon.core/capacity-analysis path-BP))

;;Do only the RA variation with fixed rc for the 1-n list.
;;We are now doing variable reps according to the ra+rc inventory.
;;24 indicates the number of logical processors which shold match
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
  ;;we can use tap> or p/submit to look at values in portal.
  (tap> input-map-AP)
  #_(p/submit input-map-AP)
  )
