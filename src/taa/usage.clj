;;meant to be loaded from the marathon repl.
;;To use the latest version of docjure with MARATHON, you need to put
;;the dependency [dk.ative/docjure "1.16.0"] in the m4 project.clj.
;;Used docjure proper instead of spork.util.excel.docjure because
;;the latest version returns nil for blank cells.  blank cells were
;;filtered out in older versions.  This allows us to copy  the data as
;;is from an xlsx worksheet and then copy the same data to a tab
;;delimited text file, similar to Excel->Save As->tab delimited text file.
(ns usage
  (:require [clojure.java.io :as java.io]
            [spork.util.table :as tbl]
            [spork.util.io :as io]
            [spork.util.excel [docjure :as doc]
             [core :as xl]]
            [dk.ative.docjure.spreadsheet :as dj]
            [demand_builder.m4plugin :as plugin]
            [marathon.analysis.random :as random]
            [marathon.analysis :as a]))

(load-file "/home/craig/workspace/taa/src/taa/core.clj")

(def resources-root "/home/craig/workspace/taa/resources/")
;;;;;;what usage.clj should be specifying:
;;path to SupplyDemand (also has a policy_map worksheet)
;; no matching clause exceptions might be for N/As in excel formulas
;; when reading workbooks into clojure. Change these values to
;; something else, like "na".
;;will also get an error when some values for idaho and RC availble
;;aren't numbers..... turn stuff to 0s or delete.
;;make sure strength in SRC_By_Day has numbers! if not, set to 0)
(def supp-demand-path
  (str resources-root "SupplyDemand_Colorado.xlsx"))
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
(def default-rc-policy "TAA_2125_Capacity_RC_1")
;;a post-process function with priority, category, and sourcefirst
;;post process demand records to copy vignette to DemandGroup and
;;set priorities
;;Change Forward stationed category to NonBOG , SourceFirst to NOT-RC-MIN
;;PTDOS are regular category =Rotational and SourceFirst = NOT-RC
;;Idaho-Competition is category = NonBOG and SourceFirst = NOT-AC
;;Idaho is category = NonBOG and SourceFirst = NOT-AC-MIN
(defn set-demand-params [{:keys [Vignette Category SourceFirst] :as r}]
                            (assoc r :DemandGroup Vignette
                                   :Priority (case Vignette
                                   "RC_NonBOG-War" 1
                                   "AlaskaFwd" 1
                                   "AlaskaRot" 6
                                   "Maine1" 2
                                   "Maine2" 2
                                   "Maine3" 2
                                   "SE-Colorado" 4
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
;;not needed.  Assume SRC_By_Day is in SupplyDemand
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

;;usually 30
(def reps 1)
;;usually 0
(def lower 1)
;;usually 1
(def upper 1)
;;20 for Keith and I on SNET
(def threads 1)

;; so the taa dir will have
;;a supply demand workbook for each demand
;;and forge output for each demand
;;maybe one timeline file?

;
;;FLOW: afer this, we output a vignettes file to the
;;Excursion_Demand_Builder/ 
;;directory.
(def builder-inputs-path (str resources-root identifier "_inputs/"))
(def outputs-path (str builder-inputs-path "/Outputs/"))
(io/make-folders! outputs-path)

(defn table->keyword-recs [table]
    (-> (tbl/keywordize-field-names table)
       (tbl/table-records)))

(defn load-workbook-recs
  "Given the path to an Excel workbook, each sheet as records and
  return a map of sheetname to records."
  [path]
  (->> (xl/as-workbook supp-demand-path)
       (xl/wb->tables)
       (reduce-kv (fn [acc sheet-name table]
                    (assoc acc sheet-name (table->keyword-recs
                                           table)))
                  {})))

(def workbook-recs (load-workbook-recs supp-demand-path))

(taa.core/vignettes-to-file (workbook-recs "SupplyDemand") vignettes
                            builder-inputs-path)

;;move the timeline to this directory (copy)
(defn copy-file [source-path dest-path]
  (java.io/copy (java.io/file source-path) (java.io/file dest-path)))

(copy-file timeline-path (str builder-inputs-path "timeline.xlsx"))
;;Excursion_SupplyRecords.xlsx gets outputted as well (maybe just put
;;this in marathon workbook when I replace demand records as well.
;;(records->xlsx (str builder-inputs-path (supply-records2226 tbls)

(def cell-type (atom nil))
;;save the SRC_by_day worksheet as tab delimitted text for demand
;;builder
;;Need to rewrite read-cell to dispatch properly per below comments.
;;Need to unmap a multimethod in order to redefine it.  Only changing
;;the dispatching method and the CellType/STRING method.
(ns-unmap 'dk.ative.docjure.spreadsheet 'read-cell)
                                        ;(ns dk.ative.docjure.spreadsheet)
(ns dk.ative.docjure.spreadsheet)
(require '[clojure.string :as string])
;;For an unkown reason, getCellType is returning an int, which doesn't
;;dispatch to any of the methods below.  One fix is to use the static
;;method forInt to return the CellType object which will dispatch properly.
(defmulti read-cell #(when % (. CellType (forInt (.getCellType ^Cell
                                                               %)))))
(defmethod read-cell CellType/BLANK     [_]     nil)
(defmethod read-cell nil [_] nil)
(defmethod read-cell CellType/STRING    [^Cell cell]
  (let [s (.getStringCellValue cell)]
    (if (string/includes? s "\n")
      ;;Need put the cell values that have a newline in them
      ;;in quotes so that it opens as tab
      ;;delimited in Excel properly.
                                        ;(str "\"" s "\"")
      (string/replace s #"\n" "")
      s)))                                                    
(defmethod read-cell CellType/FORMULA   [^Cell cell]
  (let [evaluator (.. cell getSheet getWorkbook
                      getCreationHelper createFormulaEvaluator)
        cv (.evaluate evaluator cell)]
    (if (and (= CellType/NUMERIC (.getCellType cv))
             (DateUtil/isCellDateFormatted cell))
      (.getDateCellValue cell)
      (read-cell-value cv false))))
(defmethod read-cell CellType/BOOLEAN   [^Cell cell]  (.getBooleanCellValue cell))
(defmethod read-cell CellType/NUMERIC   [^Cell cell]
  (if (DateUtil/isCellDateFormatted cell)
    (.getDateCellValue cell)
    (.getNumericCellValue cell)))
(defmethod read-cell CellType/ERROR     [^Cell cell]
  (keyword (.name (FormulaError/forInt (.getErrorCellValue cell)))))

(defn save-forge
  "Save the src by day worksheet as tab delimited text for demand
  builder.  Expect SRC_By_Day to be a worksheet in the SupplyDemand workbook."
  [supp-demand-path out-path]
  (let [worksheet-rows (->> (load-workbook supp-demand-path)
                            (select-sheet "SRC_By_Day")
                            row-seq
                            (map (fn [x] (if x (cell-seq x))))
                            (map #(reduce str (interleave
                                               (map (fn [c]
                                                      (read-cell c)) %)
                                               (repeat "\t"))))
                            ((fn [x] (interleave x (repeat "\n"))))
                            ;;One extra newline to remove at the end.
                            (butlast)
                            (reduce str))]
    (spit out-path worksheet-rows :append false)))

(ns usage)
(dj/save-forge supp-demand-path (str outputs-path "FORGE_SE-" identifier ".txt"))      

;;Take demand builder output and post process the demand
;;I think this is it...
(require 'demand_builder.forgeformatter)
(ns demand_builder.forgeformatter)
(defn read-forge [filename]
  (let [l (str/split (slurp filename) (re-pattern (System/getProperty "line.separator")))
        formatter #(if (and (str/includes? % "TP") (str/includes? % "Day"))
                       (read-num (str/replace (first (str/split % #"TP")) "Day " "")) %)
          phases (str/split (first l) #"\t")
          header (map formatter (str/split (second l) #"\t"))
          h (count (filter #(not (number? %)) header))
          formatted-phases (apply conj (map #(hash-map (first %) (second %))
                                         (filter #(not= "" (first %)) (zipmap (drop h phases) (sort (filter number? header))))))
          data (map #(str/split % #"\t") (into [] (drop 2 l)))
          formatted-data (map #(zipmap header %) (filter #(and (>= (count %) h) (not= "" (first %))) data))]
    {:header header :phases formatted-phases :data formatted-data}))
(ns usage)
(plugin/root->demand-file builder-inputs-path)

;;and place in
;;the Excursion_m4_workbook.xlsx
(def out-path (str (io/fdir base-m4) "/m4_book_" identifier ".xlsx"))

(defn replace-demand-and-supply
  "Load up a marathon workbook and replace the demand records and
  supply records."
  [m4-xlsx-path demand-path supp-demand-path out-path]
  (let [initial-tables (-> (xl/as-workbook m4-xlsx-path)
                           (xl/wb->tables))
        demand-table (->> (tbl/tabdelimited->records demand-path)
                          (into [])
                          (concat (taa.core/get-idaho+cannibal-recs
                                   workbook-recs))
                          (map set-demand-params)
                          (taa.core/records->string-name-table))
        supply-table (taa.core/supply-table workbook-recs default-rc-policy)
        table-res (merge initial-tables {"DemandRecords" demand-table
                                         "SupplyRecords" supply-table})]
    (xl/tables->xlsx out-path table-res)
        ))

(replace-demand-and-supply base-m4 (str outputs-path
                                        "Outputs_DEMAND.txt")
                                        supp-demand-path out-path)
;;Then run rand-runs on this, saving as Excursion_results.txt
;;then could co-locate a usage.py

(def proj (a/load-project out-path))
(binding [random/*threads* threads]
  (def results (random/rand-runs proj :reps reps :phases phases :lower lower
                                 :upper upper :compo-lengths
                                 compo-lengths)))
(random/write-output (str resources-root "results_" identifier ".txt") results)
)
