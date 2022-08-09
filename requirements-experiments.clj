(ns requirements-experiments
  (:require [marathon.analysis.requirements.sensitivity :as snt]
            ;;marathon.analysis.tacmm.demo has stuff for updating parameters
            [marathon.analysis.tacmm.demo :as demo]
            [spork.util [io :as io] [table :as tbl]]))
		
;;LastDayDefault indicates the last processed day of the simulation.
;;Given a MARATHON project, return the last active day of the period
;;named by period
(defn get-last-day [period proj]
  (let [last-day (->> (tbl/table-records (:PeriodRecords proj))
                      (filter (fn [r] (= (:Name r) period)))
                      (first)
                      (:ToDay))]
    ;;Hopefully not nil. Might be "inf"
    last-day))

(defn change-last-day [period proj last-day]
  (update proj :Parameters demo/xform-records
          #(demo/merge-parameters % {:LastDayDefault last-day})))
  
;;given a period name, transform the project to end after the period.
(defn update-last-day [period proj]
  ;;first get the period from the table
  (let [last-day (get-last-day period proj)]
    (if (= last-day "inf")
      ;;this is the last period, so we don't to change the end day
      identity
      (change-last-day period proj last-day))))

(defn blank-cost-file
  "Just writes a blank cost file to the comforter inputs folder since
  this is needed to comforter but we don't consider monetary cost
  anymore for requirements analysis."
  [out-dir]
  (spit (str out-dir "cost.txt") "src\ttotal_cost"))

(def add-supply-strength
  "Add a Strength field to the supply records in a MARATHON workbook
  and spit out the new workbook."
  [])


(def save-supply
  "Save supply records."
  [in-paths out-dir]
  )

(defn prep-for-requirements
  "Take the path to a marathon workbook and prep the workbook for
  requirements analysis. Save the workbook with changes."
  [m4-xlsx-path]
  (let [;;stopped here, started looking to fix project dependencies.  
        initial-tables (-> (xl/as-workbook m4-xlsx-path)
                           (xl/wb->tables))
        proportion-table (initial-tables "DemandRecords")
        supply-table (initial-tables "SupplyRecords")]

    ))

;;expect the same periods between workbooks For COMORTER, we'll output
;;our results in individual text files.
;;creates text files in out-dir named by wkbk_name-periodname-bound.txt
(defn stop-after-periods
  [in-paths period-names missed-days out-dir contour-fn]
  (let [input-recs (for [path in-paths
                         period period-names                         
                         [bound recs]
                         (binding [marathon.analysis/*table-xform*
                                   #(update-last-day period  %)]
                           (group-by :bound (contour-fn path
                                                        missed-days)))
                         :let [wkbk-name (io/drop-ext (io/fname path))
                               case (str wkbk-name "__"
                                         period "__"
                                         bound)                              
                               file-path (str out-dir case ".txt")]]
                     ;;write the output for comforter
                     (do (tbl/records->file recs file-path )
                         {:Case case :Filepath file-path}))]
    (tbl/records->file input-recs (str out-dir "input.txt"))
    (blank-cost-file out-dir)))
