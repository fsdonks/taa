1) checkout refactor branch
2) build capsule
3)
;;3 cases: the entire demand, through ph4, and through ph3
(require 'marathon.analysis.requirements.sensitivity)
(ns marathon.analysis.requirements.sensitivity)
;;given a sequence of workbook paths, put separate text files into the out dir
;;each workbook name A-ph3.xlsx
;;for each bound, output A-ph3-bound.txt, stick filenames in a text file maybe
;;each case is scenario-ph3 or ph4 or return (ends after)-CMDD
(def missed-days-2428 [0 10 20 30 60])
(def res (contours output-path missed-days))


(defn req-analysis-experiment [in-paths out-dir missed-days]
	(for [p in-paths
		[bounds recs] (group-by :bound (contours p missed-days))
		:let [wkbk-name (io/drop-ext (io/fname p))]]

marathon.analysis/table-xform
table-xform needs to operate on tables
:tables come from (proj/load-project p)
		
(def p
                                              (marathon.project/load-project "/home/craig/runs/test-run/testdata-v7-bog.xlsx"))
(:Parameters (:tables p))
;;returns a spork.util.table.column-table or parameters where columns are params and one row is values.
(tbl/table-records (:Parameters (:tables p)))
;;LastDayDefault indicates the last processed day of the simulation

;;Given a MARATHON project, return the last active day of the period named by period
(defn get-last-day [period proj]
	(let [last-day (->> (tbl/table-records (:PeriodRecrods proj))
				(filter (fn [r] (= (:Name r) period)))
				(:ToDay))]
		;;Hopefully not nil. Might be "inf"
		last-day))
		

;;given a period name
;;marathon.analysis.tacmm.demo has stuff for updating parameters
(defn update-last-day [period proj]
	;;first get the period from the table
	(let [last-day (get-last-day period proj)]
		(if (= last-day "inf")
			

;;better way to run multiple demands is have no DemandRecords or SupplyRecords in MARATHON workbook and then pull each demand 
;;output from TAA builder should be two workbooks, one with all demandrecords titled by a name and the other with all
;;supplyrecords titled by name
;;then runs are defined in [supply-name demand-name] tuples

;;given the a vector of paths to MARATHON workbooks along with a 
;;vector of period names to stop the simulation after and a vector
;;of contiguous missed demand days, creates text files in out-dir named by 
;;wkbk_name-periodname-bound.txt
;;expect the same periods between workbooks

(defn req-analysis-experiment [in-paths period-names missed-days out-dir]
	(for [path in-paths
		period period-names
		;;bind the transform here
		
		[bounds recs] (group-by :bound (contours p missed-days))
		:let [wkbk-name (io/drop-ext (io/fname p))]]
