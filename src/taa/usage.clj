(ns usage
  (:require [clojure.java.io :as java.io]
            [spork.util.table :as tbl]
            [spork.util.io :as io]
            [spork.util.excel.core :as xl]
            ))

(load-file "/home/craig/workspace/taa/src/taa/core.clj")

(def resources-root "/home/craig/workspace/taa/resources/")
;;;;;;what usage.clj should be specifying:
;;path to SupplyDemand (also has a policy_map worksheet)
(def supp-demand-path
  (str resources-root "SupplyDemand_input.xlsx"))
;;a set of vignettes to keep from SupplyDemand (ensure SupplyDemand
;;has RCAvailable and Idaho)
(def vignettes
  #{"AlaskaFwd"
    "AlaskaRot"
    "Maine1"
    "Maine2"
    "Maine3"
    ;"Colorado"
    "Wyoming"
    ;"Idaho"
    "Vermont"})
;; a default RC policy name
(def default-rc-policy "TAA22-26 RCSurge_Default_Composite")
;;a post-process function with priority, category, and sourcefirst
(defn set-demand-params [{:keys [Vignette Category SourceFirst] :as r}]
                            (assoc r :DemandGroup Vignette
                                   :Priority (case Vignette
                                   "RC_NonBOG-War" 1
                                   "AlaskaFwd" 1
                                   "AlaskaRot" 6
                                   "Maine1" 2
                                   "Maine2" 2
                                   "Maine3" 2
                                   "Colorado" 4
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
;;a path to a FORGE output
(def forge-path (str resources-root "Colorado.xlsx"))
;;a name to concatenate to the Demand_Builder dir.
(def identifier "Colorado")
;;a path to the timeline file
(def timeline-path (str resources-root "timeline_Colorado.xlsx"))
;;an excursion name to prepend to files
;;path to a base marathon file
(def base-m4 (str resources-root "base-testdata-v7.xlsx"))
;;phases
(def phases [["comp" 1 821] ["phase-1" 822 854] ["phase-2" 855 974] ["phase-3" 975 1022] ["phase-4" 1023 1789]])
;;compo-lengths for rand-runs
(def compo-lengths {"AC" 1 "RC" 2 "NG" 3})

;; so the taa dir will have
;;a supply demand workbook for each demand
;;and forge output for each demand
;;maybe one timeline file?

;
;;FLOW: afer this, we output a vignettes file to the
;;Excursion_Demand_Builder/ 
;;directory.
(def builder-inputs-path (str resources-root identifier "_inputs/"))
(io/make-folders! builder-inputs-path)

(defn load-workbook-recs
  "Given the path to an Excel workbook, load the first sheet as records."
  [path]
  (-> (xl/as-workbook path)
       (xl/wb->tables)
       ((fn [tbls] (second (first tbls))))
       (tbl/keywordize-field-names)
       (tbl/table-records)))

(defn records->string-name-table [recs]
  (->> (tbl/records->table recs)
       (tbl/stringify-field-names)
  ))

(defn records->xlsx [wbpath sheetname recs]
  (->> (records->string-name-table recs)
       (xl/table->xlsx wbpath sheetname)
       ))


(->> (load-workbook-recs supp-demand-path)
     (map (fn [r]
            (select-keys r (concat [:SRC :UNTDS]
                                   (map keyword vignettes)))))
     (taa.core/get-vignettes)
     (records->xlsx (str builder-inputs-path
                         "vignettes.xlsx") "Sheet1"))


;;move the timeline to this directory (copy)
(defn copy-file [source-path dest-path]
  (java.io/copy (java.io/file source-path) (java.io/file dest-path)))

(copy-file timeline-path (str builder-inputs-path "timeline.xlsx"))
;;Excursion_SupplyRecords.xlsx gets outputted as well (maybe just put
;;this in marathon workbook when I replace demand records as well.
(def tbls (xl/wb->tables (xl/as-workbook supp-demand-path)))
;(records->xlsx (str builder-inputs-path (supply-records2226 tbls)
;;save the SRC_by_day worksheet as tab delimitted text for demand builder
;;Take demand builder output and post process the demand and place in
;;the original directory  as Excursion_DemandRecords.xlsx
;;the Excursion_m4_workbook.xlsx
;;Then run rand-runs on this, saving as Excursion_results.txt
;;then could co-locate a usage.py

;;;;;what we used to do:
;;delete all worksheets except for supplydemand.  maybe
;;need new FORGE

;; no matching clause exceptions might be for N/As in excel formulas when reading workbooks into clojure. Change these values to something else, like "na".

;; Move out vignettes to a "vignettes" worksheet (exclude Idaho and
;; Colorado)
;;better: provide a list of vignettes to keep here in usage, so some
;; function creates tbls with vignettes

;; These instructions are meant for the taa Clojure project.
;;load this from marathon:

(load-file "/home/craig/workspace/taa/src/taa/core.clj")
(ns taa2327)
;;override the workbook path in taa2327 to point to the supply demand
;;file
(def wbpath
  (str resources-root "SupplyDemand_input.xlsx"))
;;need to override the tables as well
(def tbls (xl/wb->tables  (xl/as-workbook wbpath)))

(paste-hld+cannibal! tbls) ;will give records for hld and cannibalized records.
;still got an error because some values aren't a number..... turn stuff to 0s or delete.

(get-vignettes tbls) ;will return the vignettes for demand builder

(supply-records2226 tbls)
;supply-records2226 will return the supply records

Now, to run demand_builder:

;;no input map is needed
(require 'demand_builder.m4plugin)
(ns demand_builder.m4plugin)
(def t2337 "K:\\Divisions\\FS\\_Study Files\\TAA
  23-27\\Inputs\\Demand_Builder\\")
;;I think this is it...
(root->demand-file t2337)
;;this might throw an error because of the SRC_By_Day newline errors, but necessary to set up files in Ouputs/ (although it might not...)
;;error is NullPointerException java.util.regex.Matcher.getTextLength (:-1)

;(make sure strength has number! if not, set to 0)
;no strength for the following SRCs:

;;open each Forge file and save SRC_By_Day sheet as tab delimited in Outputs/
(require 'demand_builder.formatter)
(ns demand_builder.formatter)
(root->demandfile (str demand_builder.m4plugin/t2337 "Outputs/"))

;;use post-process-demand function to do this (copy demandrecords first)

;now, change priorities according to the parameters word document
;copy vignette to demandgroup

(capacity-analysis "somepath/m4book-2327.xlsx")

marathon.analysis.random
(comment
;;way to invoke function
(def path "somepath/m4book-2327.xlsx")
(def proj (a/load-project path))
(def phases [["comp" 1 821] ["phase-1" 822 854] ["phase-2" 855 974] ["phase-3" 975 1022] ["phase-4" 1023 1789]])
(def cuts_1 (rand-runs proj 16 phases 0 1.5))
(def cuts_2 (rand-runs proj 16 phases 0 1.5))
)
