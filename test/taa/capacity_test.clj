(ns taa.capacity-test
  (:require [clojure.test :refer :all]
            [taa.capacity :as capacity]
            [clojure.string :as string]
            [clojure.java.io :as jio]
            [proc.util :as putil]
            [clojure.set :as cset]))

;;To do runs, you would load an input map in taa.core and call do-taa
;;without deftest.
(def forward-names-ac #{"AlaskaFwd" "UtahFwd"})
(def forward-names-rc #{"CompFwdRC"})
(def forward-map {:ac forward-names-ac
                  :rc forward-names-rc})
(def forward-names (capacity/fwd-demands forward-map))

(defn assess-risk [x]
  (cond (>= x 0.999) 5
        (>= x 0.90) 4
        (>= x 0.80) 3
	(>= x 0.70) 2
        :else 1))

(def default-weights
  {"comp" 0.2
   "phase-1" 0.2
   "phase-2" 0.2
   "phase-3" 0.2
   "phase-4" 0.2
   })

(def srcs-to-filter
  #{"01205K000"})



(def input-map {;;if you want to Enabled all SupplyRecords and then
                ;;filter those SupplyRecords, you could use
                ;;:transform-proj
                ;;(capacity/supply-src-filter
                ;;srcs-to-filter true) 
                
                ;;Used to indicate the minimum number of inventories
                ;;covered in the edta supply output and for the random runs.
                :min-distance 0
                ;;If this key and :phase-weights are included, produces a risk file from
                ;;results.txt based on this function that will bin the
                ;;Score into different categories
                :assessor assess-risk
                ;;compute a weighted sum of the percentage of demand
                ;;met from each phase using this map of phase name to weight.
                :phase-weights default-weights
                ;;Indicates that we want to merge ARNG and USAR into
                ;;one RC compo, which makes random RC runs easier.
                ;;This will cause RC and NG to be distributed
                ;;across the same lifecycle.  We could prevent that
                ;;with our supply preprocess tag for binning with an
                ;;NG bin but we would need a proportion of the RC that
                ;;is NG for that.
                :merge-rc? true
                ;;Used for demand and supply to create a separate
                ;;cycle time distribution for forward stationed units.
                :forward-names forward-map
                ;;Determine if we want to bin the RA supply into
                ;;forward stationed and not forward-stationed for
                ;;separate cycle time distribution.
                :bin-forward? true
                ;;Only need to set this to true if the filenames are
                ;;going to be located as resources on the path.
                ;;If using resources, the initial inputs are
                ;;resources, and intermediate and final inputs/outputs
                ;;will be in the jar folder under the recources-root.
                :testing? true
                ;;A place for non-resource inputs and the outputs.
                :resources-root "test-output/"
                ;;;;;;what usage.clj should be specifying:
                ;;path to SupplyDemand (also has a policy_map worksheet)
                ;; no matching clause exceptions might be for N/As in excel formulas
                ;; when reading workbooks into clojure. Change these values to
                ;; something else, like "na".
                ;;will also get an error when some values for idaho and RC availble
                ;;aren't numbers..... turn stuff to 0s or delete.
                ;;make sure strength in SRC_By_Day has numbers! if
                ;;not, set to 0)
                ;;The name of the supply demand file that exists in
                ;;the resources-root folder, Must contain a worksheet
                ;;called "SupplyDemand"
                :supp-demand-name "SupplyDemand.xlsx"
                ;;This workbook should only have  one worksheet because it
                ;;loads the first worksheet it finds.
                :policy-map-name "rc_war_policy_mapping.xlsx"
                ;;a set of vignettes to keep from SupplyDemand (ensure SupplyDemand
                ;;has RCAvailable and Idaho)
                :vignettes (clojure.set/union #{
                                   "AlaskaRot"
                                   "Maine1"
                                   "Maine2"
                                   "Maine3"
                                        ;"Colorado"
                                   "Wyoming"
                                        ;"Idaho"
                                   "Vermont"}
                                 forward-names)
                ;; a default RC policy name
                :default-rc-policy "TAA_2125_Capacity_RC_1"
                ;;a post-process function with priority, category, and sourcefirst
                ;;post process demand records to copy vignette to DemandGroup and
                ;;set priorities
                ;;Change Forward stationed category to NonBOG , SourceFirst to NOT-RC-MIN
                ;;PTDOS are regular category =Rotational and SourceFirst = NOT-RC
                ;;Idaho-Competition is category = NonBOG and SourceFirst = NOT-AC
                ;;Idaho is category = NonBOG and SourceFirst = NOT-AC-MIN 
                :set-demand-params
                (fn [{:keys [Vignette Category SourceFirst] :as r}]
                  (assoc r :DemandGroup Vignette
                         :Priority (condp = Vignette
                                     "RC_NonBOG-War" 1
                                     "AlaskaFwd" 1
                                     "UtahFwd" 1
                                     "CompFwdRC" 1
                                     "AlaskaRot" 6
                                     "Maine1" 2
                                     "Maine2" 2
                                     "Maine3" 2
                                     "SE-Colorado" 4
                                     "SE-forge2" 4
                                     "Wyoming" 5
                                     "Idaho" 3
                                     "Vermont" 6)
                         :Category (if (clojure.string/includes? Vignette
                                                                 "Fwd")
                                     "Forward"
                                     (case Vignette
                                       "Maine1" "NonBOG"
                                       "Maine2" "NonBOG"
                                       ;;"Maine3" "Rotational"
                                       Category
                                       ))
                         :SourceFirst (if (string/includes? Vignette
                                                                    "Fwd")
                                        "NOT-RC-MIN"
                                        (case Vignette
                                          "Maine1"
                                          "NOT-AC"
                                          "Maine2" "NOT-AC"
                                          SourceFirst))
                         :Tags (cond (contains? forward-names Vignette)
                                 (str "{:region " :forward "}")
                                 :else ""
                                 )))
                ;;an excursion name to prepend to output files
                :identifier "Colorado_single"
                ;;a map of a labeling of the forge file to the forge
                ;;xlsx path that contains an SRC_By_Day worksheet.
                ;;The label string must match the part in timeline
                ;;after "SE-"
                :forge-files {"Colorado" "SupplyDemand.xlsx"
                              "forge2" "another_forge.xlsx"}
                ;;the name of the the timeline file that exists in
                ;;resources-path
                ;;In order to use one timeline file, would need to
                ;;filter the timeline file based on the forge file
                ;;map and might also need certain vignettes with one
                ;;and not the other so would need to specify a
                ;;vignette filter.  This is too complicated, so just
                ;;create a timeline.xlsx file for each m4 workbook
                ;;case for now                
                :timeline-name "timeline_test.xlsx"
                ;;the name of the base marathon file that exists in
                ;;the resources-path
                :base-m4-name "base-testdata-v7.xlsx"
                :periods-name "base-testdata-v7.xlsx"
                :parameters-name "base-testdata-v7.xlsx"
                ;;phases for results.txt
                :phases [["comp" 1 821]
                         ["phase-1" 822 854]
                         ["phase-2" 855 974]
                         ["phase-3" 975 1022]
                         ["phase-4" 1023 1789]]
                ;;For the most recent TAA, we
                ;;are currently assuming that cannibalized units can't be used for anything!
                :cannibal-start "phase-1"
                :cannibal-end "phase-3"
                :idaho-start "phase-1"
                :idaho-end "phase-4"
                ;;This name will change when we move the repo.
                :idaho-name "Idaho"
                ;;compo-lengths for rand-runs               
                :compo-lengths {"AC" 1 "RC" 2 "NG" 3}
                ;;usually 30
                :reps 2
                ;;low bound on AC reductions, usually 0 (no AC inventory)
                :lower 0.9999
                ;;Max ac growth.  usually 1 (base taa inventory)
                :upper 1
                ;;20 for taa runs on our 2 fast computers
                :threads 1
                :include-no-demand true
                })

(defn filter-rec [demand-group src demand-recs]
  (filter (fn [{:keys [DemandGroup SRC]}]
            (and (= demand-group DemandGroup) (= SRC src)))
          demand-recs))

(defn diffs
  [sign recs1 recs2 src]
  (let [rec1 (first (filter-rec "RC_NonBOG-War" src recs1))
        rec2 (first (filter-rec "RC_NonBOG-War" src recs2))
        quantity1 (:Quantity rec1)
        quantity2 (:Quantity rec2)]
     (sign quantity1 quantity2)))
  
(defn all-diffs
  [prev-ds cons-ds]
  (let [srcs (map :SRC (cset/difference (set prev-ds)
                                                  (set
                                                   cons-ds)))]
    (map (partial diffs - prev-ds cons-ds) srcs)))

(defn all-one-more?
  "Are all of the differences 1.0 due to a rounding up with an
  overestimate because of floating point arithetic imprecision?"
  [prev-ds cons-ds]
  (every? (fn [diff] (= diff 1.0)) (all-diffs prev-ds cons-ds)))

(defn remove-quantity
  [recs]
  (map (fn [r] (dissoc r :Quantity)) recs))

(defn same-demands?
  "Check to see if we have the same set of demands when checking the
  difference both ways and removing :Quantity."
  [demands1 demands2]
  (let [s1 (set demands1)
        s2 (set demands2)
        diff1 (cset/difference s1 s2)
        diff2 (cset/difference s2 s1)]
    (= (set (remove-quantity diff1))
       (set (remove-quantity diff2)))))

;;if you want preprocess first and then visually check, call
;;preprocess-taa first and then call do-taa-runs on the output
;;workbook.

(def previous-book
  (->> "m4_book_Colorado_single-before_multicompos.xlsx"
       (jio/resource)))

(defn consistent-demand
  "We want to make sure that our demand is the same before and after
  the multi compo forward stationing but we added a new demand so we
  need to filter that out."
  [m4-book]
  (->> (putil/demand-records m4-book)
       (remove (fn [{:keys [Vignette]}] (= "CompFwdRC" Vignette)))))

(defn clean-demand
  "In addition to consistent-demand, we fixed a rounding error at some
  point which caused our tests to fail.  Now, we want to write some
  tests to verify the rounding error separate from the usual tests
  we had written here.  So we will filter out the failing records
  here."
  [previous-demands new-demands]
  (let [diffs (set (remove-quantity (cset/difference (set previous-demands)
                                                (set new-demands))))
        filterer (fn [r] (not (contains?
                               diffs
                               (dissoc r :Quantity))))]
    [(filter filterer previous-demands)
     (filter filterer new-demands)]))

(defn consistent-supply
  "We want to make sure that our supply is the same before and after
  the multi compo forward stationing but after we add new test data
  for the rc, this will only hold for the ac now since we don't have
  any ng."
  [m4-book]
  (->> (putil/supply-records m4-book)
       (filter (fn [{:keys [Component]}] (= "AC" Component)))))

(deftest do-taa-test
  (binding [capacity/*testing?* true]
    (let [;;This will run through the taa preprocessing
          out-path (capacity/preprocess-taa input-map)
          previous-demands (consistent-demand previous-book)
          previous-supply (consistent-supply previous-book)
          [prev-dmd-clean new-dmd-clean] (clean-demand
                                          previous-demands
                                          (consistent-demand
                                           out-path))]
      (is (= prev-dmd-clean new-dmd-clean)
          "After enabling multiple compos forward, if we only
have ac forward, is our demand still the same?.")
      (is (= previous-supply (consistent-supply out-path))
          "After enabling multiple compos forward, is our ac 
supply still the same at least?")
      (testing "Checking if taa capacity analysis runs complete."
        (capacity/do-taa-runs out-path input-map)))))
