(ns taa.core-test
  (:require [clojure.test :refer :all]
            [taa.core :refer :all]))

;;To do runs, you would load an input map in taa.core and call do-taa
;;without deftest.

(def input-map {;;Only need to set this to true if the filenames are
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
                :vignettes #{"AlaskaFwd"
                             "AlaskaRot"
                             "Maine1"
                             "Maine2"
                             "Maine3"
                                        ;"Colorado"
                             "Wyoming"
                                        ;"Idaho"
                             "Vermont"}
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
                         :Priority (case Vignette
                                     "RC_NonBOG-War" 1
                                     "AlaskaFwd" 1
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
                                     "NonBOG"
                                     (case Vignette
                                       "Maine1" "NonBOG"
                                       "Maine2" "NonBOG"
                                       ;;"Maine3" "Rotational"
                                       Category
                                       ))
                         :SourceFirst (if (clojure.string/includes? Vignette
                                                                    "Fwd")
                                        "NOT-RC-MIN"
                                        (case Vignette
                                          "Maine1"
                                          "NOT-AC"
                                          "Maine2" "NOT-AC"
                                          SourceFirst))))
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

(deftest capacity
    (testing "Just checking if highest level taa function completes
    for capacity analysis preprocessing and runs."
      (do-taa input-map)))

;;if you want preprocess first and then visually check, call
;;preprocess-taa first and then call do-taa-runs on the output
;;workbook.
