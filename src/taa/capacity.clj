;;This namespace is used to preprocess TAA inputs and do TAA runs
;;using inputs like those specified in the accompanying usage.clj.
(ns taa.capacity
  (:require [spork.util.table :as tbl]
            [spork.util.clipboard :as board]
            [clojure.java.io :as java.io]
            [spork.util.io :as io]
            [spork.util.excel [docjure :as doc]
             [core :as xl]]
            [dk.ative.docjure.spreadsheet :as dj]
            [demand_builder.m4plugin :as plugin]
            [marathon.analysis.random :as random]
            [marathon.analysis :as a]))

;;indicate that we should load resources from the jar as opposed to
;;the file system
(def ^:dynamic *testing?* false)
;;;;;;;;;;;;;;;;;;;;;;;;;
;;utility functions
(defn columns->records
  "Given a sequenc of records, keep all fields specified by a
  flds sequence and put remaining
  field values into a new column name by k and each remaining
  field key into a column named by fld.
  Assume that each record has the same fields."
  [recs flds k fld]
  (mapcat (fn [r] (let [baser (select-keys r flds)
                        nfs (apply dissoc r (keys baser))]
                    (if (> (count nfs) 0)
                      (for [[key v] nfs]
                        (assoc baser k v
                               fld (name key)))
                      [r] ) ) ) recs) )

(defn paste-ordered-records!
  "Pastes a sequence of records, xs, to the clipboard as a string
  that assumes
  xs are records in a tabdelimited table."
  [xs order]
  (board/paste! (tbl/table->tabdelimited (tbl/order-fields-by
                                          order
                                          (tbl/records->table
                                           xs)))))

(defn round-to
  "rounds a number, n to an integer number of (num) decimals"
  [num n]
  (read-string (format (str "%." num "f") (float n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;demand building tools
(def nonbog-record {:Vignette "RC_NonBOG-War"
                    :Type "DemandRecord"
                    :Category "NonBOG-RC-Only"
                    :Overlap 45
                    :SourceFirst "NOT-AC-MIN"
                    :Operation "RC_NonBOG-War"
                    :Enabled "TRUE"
                    :Demandindex "l"
                    :Priority 4
                    (keyword "Title 10_32") 10
                    :Quantity nil
                    :SRC nil
                    :DemandGroup "RC_NonBOG-War"
                    :OITitle "unnecessary"})

(defn idaho-record [name]
  (assoc nonbog-record
                         :Vignette name
                         :Category "NonBOG"
                         :Operation name
                         :SourceFirst "NOT-AC-MIN"
                         :Priority 1
                         :DemandGroup name))

(defn branch-average [src-unavail]
  (let [groups ( group-by ( fn [[src unavail]] ( subs src 0 2)) src-unavail)]
    ( into {} (map ( fn [ [ group rs] ] 
                    [ group ( float (/ ( reduce + (map second rs) ) 
                                       ( count rs) ) ) ] ) groups) ) ) )

(defn idaho+cannibal-recs 
  [src-rcsupply src-war-idaho src-unavail {:keys [phases
                                                  cannibal-start
                                                  cannibal-end
                                                  idaho-start
                                                  idaho-end
                                                  idaho-name]}]
  (let [[_ cannibal-start-t _]
        (first (filter (fn [[phase-name]]
                         (= phase-name cannibal-start)) phases))
        [_ _ cannibal-end-t]
        (first (filter (fn [[phase-name]]
                         (= phase-name cannibal-end)) phases))
        [_ idaho-start-t _]
        (first (filter (fn [[phase-name]]
                         (= phase-name idaho-start)) phases))
        [_ _ idaho-end-t]
        (first (filter (fn [[phase-name]]
                         (= phase-name idaho-end)) phases))
        unavail5 (into {} (map (fn [ [s unavail]] [ (subs s 0 5)
                                                   unavail]) src-unavail))
        averages (branch-average src-unavail)
        average (float (/ (reduce + (map second src-unavail))
                          (count src-unavail)))]
    (->> (for [[src supply] src-rcsupply
               :let [unavail-percent (if-let [u (src-unavail src)]
                                       u
                                       (if-let [u (unavail5 (subs
                                                             src 0 5))]
                                         u
                                         (if-let [u (averages (subs src 0 2))]
                                           u
                                           average)))
                     unavail (round-to 0 (* unavail-percent supply))
                     diff (- unavail (if-let [h (src-war-idaho src)]
                                       h
                                       0))
                     ;;We used to use the cannibalized supply for idaho
                     ;;Now we assume that the unavailable
                     ;;number cannot be used for idaho.
                     nonbog 
                     (assoc nonbog-record
                            :Quantity unavail
                            :SRC src
                            :StartDay cannibal-start-t
                            :Duration (inc (- cannibal-end-t
                                              cannibal-start-t)))
                     idaho-quantity (- unavail diff)]]
                                        ;(cond
                                        ;(= diff 0) [(assoc idaho-record :Quantity unavail :SRC src)]
           
                                        ;(< diff 0) [(assoc idaho-record :Quantity (- unavail
                                        ;                                          diff) :SRC src)]
                                        ;:else [(assoc idaho-record :Quantity (- unavail
                                        ;                                   diff) :SRC src)
                                        ; (assoc nonbog-record :Quantity diff :SRC
                                        ;     src)]
           (if (or (= idaho-quantity (float 0))
                   (= idaho-quantity 0))
             ;;Don't need idaho, but even if the nonbog quantity is 0,
             ;;we keep it so that we could assign a quantity to it
             ;;later if we grow rc supply.
             [nonbog]
             ;;need idaho record, too
             [nonbog 
              (assoc (idaho-record idaho-name)
                     :Quantity idaho-quantity
                     :SRC src
                     :StartDay idaho-start-t
                     :Duration (inc (- idaho-end-t idaho-start-t)))
              ]))
         (reduce concat))))

(def demand-records-root-order
  [:Type
   :Enabled
   :Priority
   :Quantity
   :DemandIndex
   :StartDay
   :Duration
   :Overlap
   :SRC
   :SourceFirst
   :DemandGroup
   :Vignette
   :Operation
   :Category] )

(defn records->string-name-table [recs]
  (->> (tbl/records->table recs)
       (tbl/stringify-field-names)
       ))

(defn get-idaho+cannibal-recs [workbook-recs {:keys [idaho-name] :as input-map}]
  (let [available-rc (->> (workbook-recs "SupplyDemand")
                          (reduce (fn [acc {:keys [SRC
                                                   RCAvailable] } ]
                                    (if (number? RCAvailable)
                                      (assoc acc SRC RCAvailable)
                                      acc) ) {}) )
        rc-supply (->> (workbook-recs "SupplyDemand")
                       (map (fn [{:keys [SRC ARNG USAR]}] [SRC
                                                           (reduce + (remove nil? [ARNG USAR]))]))
                       (into {}))
        rc-unavail (->> (for [[src supply] rc-supply
                              ;;when we have a available number for the src
                              ;;otherwise, this will defer to
                              ;;idaho+cannibal-recs to find unavailable
                              :when (available-rc src)]
                          [src (- 1 (if (zero? supply) 0
                                        (/ (available-rc src)
                                           supply)
                                        ;;stopped.  should just rescan
                                        )) ])
                        (into {} ) )
        src-war-idaho (->> (workbook-recs "SupplyDemand")
                           (reduce (fn [acc {:keys [SRC] :as r}]
                                     (assoc acc SRC (get r (keyword idaho-name)))) {})) ]
    (idaho+cannibal-recs rc-supply src-war-idaho rc-unavail input-map)))

(defn get-vignettes
  "Return the vignette table records used for Demand Builder."
  [vignette-recs]
  (->> (columns->records vignette-recs
                         [:SRC :UNTDS] :Quantity :ForceCode)
       (map (fn [ {:keys [SRC] :as r} ]
              (assoc (clojure.set/rename-keys r {:UNTDS :Title})
                     :SRC2 (subs SRC 0 2)
                     :Strength 1
                     :Title10_32 10
                     :Non-Rot ""
                     :Comments "") ) )
       (remove (fn [r] (or (nil? (:Quantity r))
                           (zero?
                            (:Quantity r)))))))

(defn records->xlsx [wbpath sheetname recs]
  (->> (records->string-name-table recs)
       (xl/table->xlsx wbpath sheetname)
       ))

(defn vignettes-to-file
  "Given the standard supply demand records with vignettes as columns,
  select only the columns for the vignettes we need, put them in a
  long table format, and then output the vignettes to an excel file
  for demand builder."
  [raw-vignette-recs vignette-names out-dir]
  (->> raw-vignette-recs
       (map (fn [r]
              (select-keys r (concat [:SRC :UNTDS]
                                     (map keyword vignette-names)))))
       (get-vignettes)
       (records->xlsx (str out-dir
                           "vignettes.xlsx") "Sheet1")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;supply building tools
(def other-fields
  {:Type "Supply Record",
   :Enabled "TRUE",
   :Quantity 11,
   :Name "Auto",
   :Behavior "Auto",
   :CycleTime 0,
   :Policy "Auto",
   :Tags "Auto",
   :Spawntime 0,
   :Location "Auto",
   :position "Auto"})

(defn forward-quantities
  "Returns a map of SRC to the forward stationed quantity."
  [record-map {:keys [forward-name bin-forward?]}]
  (->> (record-map "SupplyDemand")
       (reduce (fn [acc {:keys [SRC] :as r} ]
                 (let [quantity (r (keyword forward-name))]
                   (if (and bin-forward?
                            (number? quantity)
                            (not (zero? quantity)))                                      
                     (assoc acc SRC (int quantity))
                     acc) )) {}) ))

(defn tag-supply
  "Return a tag for all supply records.  Currently only binning the forward
  stationed units."
  [compo src forward-nums forward-name]
  (let [forward-num (forward-nums src)]
    (if (and (= compo "RA") (not (nil? forward-num)))
      (str "{:preprocess [align-units [[:"
           forward-name " " forward-num "]]]}"))))

(defn supply-table
  "Given" [record-map rc-default-policy input-map]
  (let [rs (->> (record-map "SupplyDemand")
                (map ( fn [r] ( select-keys r
                               [:SRC :UNTDS :RA :ARNG :USAR]))))
        tblr (->> (columns->records rs
                                    [:SRC :UNTDS] :Quantity :Component)
                  (map (fn [{:keys [Quantity] :as r}]
                         (if Quantity
                           r
                           ;;Need 0 quantities for a requirements
                           ;;analysis record if we are binning forward
                           ;;stationed supply.
                           ;;For capacity analysis, 0 supply quantity
                           ;;would be out of scope.
                           (assoc r :Quantity 0)))))
        policy-map (->> (record-map "policy_map") 
                        (reduce (fn [acc {:keys [SRC
                                                 CompositePolicyName]}]
                                  (assoc acc SRC
                                         CompositePolicyName)) {}))
        policy-map5 ( into {} (map ( fn [ [ s policy] ] [ ( subs s 0 5)
                                                         policy])
                                   policy-map))
        forward-nums (forward-quantities record-map input-map)
        final-recs (for [{:keys [Component SRC] :as r} tblr
                         :let [policy (if-let [p (policy-map
                                                  SRC)]
                                        p
                                        (if-let [p (policy-map5
                                                    (subs SRC 0 5))]
                                          p
                                          rc-default-policy))]]
                     (assoc
                      (clojure.set/rename-keys (merge other-fields
                                                      r) {:UNTDS :OITitle})
                      :Component (case Component "RA" "AC" "ARNG"
                                       "NG" "USAR" "RC")
                      :Policy (if (= Component "RA") "Auto"
                                  policy)
                      :Tags  (tag-supply Component SRC forward-nums
                                         (:forward-name input-map))))]
    (records->string-name-table final-recs)
                                        ;(paste-ordered-records! final-recs
                                        ;[:Type :Enabled :Quantity :SRC :Component :OITitle :Name
                                        ;:Behavior :CycleTime :Policy :Tags :Spawntime :Location :Position]
    ))

;; so the taa dir will have
;;a supply demand workbook for each demand
;;and forge output for each demand
;;maybe one timeline file?
(defn table->keyword-recs [table]
  (-> (tbl/keywordize-field-names table)
      (tbl/table-records)))

(defn in-uberjar?
  "Determines if we are running inside an uberjar or not.  This seems
  to work and returns true from MARATHON and the taa uberjar.  This
  will return false if loading a repl from a project using Nightcode
  or when cider jacking in to a project."
  []
  (= *file* "NO_SOURCE_PATH"))

(defn as-workbook
  "Loads an Excel workbook from resources if we are running tests, or loads the workbooks from filepath."
  [filepath]
  (if *testing?*
    (dj/load-workbook-from-resource filepath)
    (xl/as-workbook filepath)))

  
(defn load-workbook-recs
  "Given the path to an Excel workbook, each sheet as records and
  return a map of sheetname to records."
  [path]
  (->> (as-workbook path)
       (xl/wb->tables)
       (reduce-kv (fn [acc sheet-name table]
                    (assoc acc sheet-name (table->keyword-recs
                                           table)))
                  {})))

(defn copy-file [source-path dest-path]
  (java.io/copy (java.io/file source-path) (java.io/file dest-path)))

(defn copy-resource! [resource-filename new-dir]
  (let [resource-file (java.io/resource resource-filename)
        tmp-file (java.io/file new-dir resource-filename)]
    (with-open [in (java.io/input-stream resource-file)] (java.io/copy in tmp-file))))

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
  builder.  Expect SRC_By_Day to be a worksheet in the xlsx file
  located at forege-path."
  [forge-path out-path]
  (let [worksheet-rows (->> (if taa.capacity/*testing?* (load-workbook-from-resource
                                           forge-path)
                                (load-workbook forge-path))
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

(ns taa.capacity)   

;;Take demand builder output and post process the demand
;;I think this is it...
(require 'demand_builder.forgeformatter)
(ns demand_builder.forgeformatter)
(defn read-forge [filename]
  (let [l (str/split (slurp filename) #"\n")
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
(ns taa.capacity)

(defn replace-demand-and-supply
  "Load up a marathon workbook and replace the demand records and
  supply records."
  [m4-xlsx-path demand-path supp-demand-path out-path workbook-recs
   default-rc-policy set-demand-params periods-path parameters-path input-map]
  (let [initial-tables (-> (as-workbook m4-xlsx-path)
                           (xl/wb->tables))
        period-table ((-> (as-workbook periods-path)
                          (xl/wb->tables)) "PeriodRecords")
        parameter-table ((-> (as-workbook parameters-path)
                             (xl/wb->tables)) "Parameters")
        demand-table (->> (tbl/tabdelimited->records demand-path)
                          (into [])
                          (concat (taa.capacity/get-idaho+cannibal-recs
                                   workbook-recs input-map))
                          (map set-demand-params)
                          (taa.capacity/records->string-name-table))
        supply-table (taa.capacity/supply-table workbook-recs
                                                default-rc-policy input-map)
        table-res (merge initial-tables {"DemandRecords" demand-table
                                         "SupplyRecords" supply-table
                                         "PeriodRecords" period-table
                                         "Parameters" parameter-table})]
    (xl/tables->xlsx out-path table-res)
    ))

(require '[marathon.processing.pre :as pre] 
         '[marathon.ces.core :as core]
         '[marathon.ces.fill.scope :as scope]
         '[proc.supply :as supply])

(def zero-results
  {:rep-seed 0
   :SRC 0
   :phase 0
   :AC-fill 0
   :NG-fill 0
   :RC-fill 0
   :AC-overlap 0
   :NG-overlap 0
   :RC-overlap 0
   :total-quantity 0
   :AC-deployable 0
   :NG-deployable 0
   :RC-deployable 0
   :AC-not-ready 0
   :NG-not-ready 0
   :RC-not-ready 0
   :AC-total 0
   :NG-total 0
   :RC-total 0
   :AC 0
   :NG 0
   :RC 0})

(defn no-demands
  "Given a marathon project, return a sequence of SRCs that have
  supply but no demand."
  [proj]
  (->> proj
       (a/load-context)
       (core/get-fillstore)
       ((fn [fillstore] (get fillstore :fillgraph)))
       (scope/derive-scope)
       (:out-of-scope)
       (filter (fn [[src reason]] (= "No Demand" reason)))
       (map first)))

(defn compo-quantities
  "Return a map of {src {'AC' quantity 'NG' quantity 'RC' quantity} from a marathon project."
  [proj]
  (->> proj
       (:tables)
       (:SupplyRecords)
       (tbl/table-records)
       (supply/quants-by-compo)))

(defn add-no-demand
  "Given a marathon project, generate results.txt records of all 0s
  for SRCs that don't have any demand.  These should appear at the top
  of the 1-n list."
  [proj reps phases lower upper]
  (let [quantity-map (compo-quantities proj)]
    (for [src (no-demands proj)
          rep (range reps)
          [p start end] phases
          :let [compo-map (quantity-map src)
                ac-quantity (compo-map "AC")
                [low high]   (random/bound->bounds
                              ac-quantity [lower upper])]
          n (if (= low high)
              [ac-quantity]
              (random/compute-spread-descending (inc high) low high))]
      (assoc zero-results
             :rep-seed rep
             :SRC src
             :phase p
             :AC n
             :NG (get compo-map "NG" 0)
             :RC (get compo-map "RC" 0)))))

;;Then run rand-runs on this, saving as Excursion_results.txt
;;then could co-locate a usage.py

(defn do-taa-runs [in-path {:keys [identifier
                                   resources-root
                                   phases
                                   compo-lengths
                                   reps
                                   lower
                                   upper
                                   threads
                                   include-no-demand]}]
  (let [proj (a/load-project in-path)
        results
        (binding [random/*threads* threads]
          (random/rand-runs proj :reps reps :phases phases :lower lower
                            :upper upper :compo-lengths
                            compo-lengths))
        results (if include-no-demand (concat results
                                              (add-no-demand
                                               proj
                                               reps
                                               phases
                                               lower
                                               upper))
                    results)]
    (random/write-output (str resources-root "results_" identifier ".txt") results)))

;;Best way to structure taa inputs?
;;might use the same timeline, so keep the path specified to that and
;;the supply demand (so that we don't have to rename supplydemand)
;;so don't loop over each excursion.

;;look like all literal values, so make one function call (do-taa) in usage
;;with a map, then I simply need to load-file usage (keep this call
;;commented in usage and keep an accompanying clj file to call it
(defn prep-builder-files
  "Setup directories and input files for demand builder."
  [builder-inputs-path
   workbook-recs
   outputs-path
   {:keys [resources-root
           supp-demand-path
           vignettes
           default-rc-policy
           identifier
           timeline-name
           forge-files] :as input-map}]
  ;;setup
  (io/make-folders! (str builder-inputs-path "/Outputs/"))
  (taa.capacity/vignettes-to-file (workbook-recs "SupplyDemand") vignettes
                              builder-inputs-path)
  ;;move the timeline to this directory (copy)
  (if *testing?*
    (copy-resource! timeline-name builder-inputs-path)
    (copy-file (str resources-root timeline-name) (str
                                                   builder-inputs-path
                                                   "timeline.xlsx")))
  (doseq [[forge-name forge-file-name] forge-files
          :let [in-path (if *testing?*
                          forge-file-name
                          (str resources-root forge-file-name))]]
          (dj/save-forge in-path (str outputs-path "FORGE_SE-"
                                      forge-name ".txt"))))

(defn preprocess-taa
  "Does all of the input preprocessing for taa. Returns the path to
  the m4 workbook that was generated as a result."
  [{:keys [resources-root
           supp-demand-name
           policy-map-name
           vignettes
           default-rc-policy
           set-demand-params
           identifier
           base-m4-name
           phases
           compo-lengths
           reps
           lower
           upper
           threads
           periods-name
           parameters-name] :as input-map}]
  (let [in-root (if *testing?* "" resources-root)
        supp-demand-path (str in-root supp-demand-name)
        policy-map-path (str in-root policy-map-name)
        input-map (assoc input-map :supp-demand-path supp-demand-path)
        base-m4-path (str in-root base-m4-name)
        workbook-recs {"SupplyDemand" ((load-workbook-recs
                                        supp-demand-path) "SupplyDemand")
                       "policy_map" (first (vals (load-workbook-recs
                                                  policy-map-path)))}
        builder-inputs-path (str resources-root identifier "_inputs/")
        outputs-path (str builder-inputs-path "/Outputs/")
        ;;and place in
        ;;the Excursion_m4_workbook.xlsx
        out-path (str resources-root "/m4_book_" identifier
                      ".xlsx")]
    ;;setup files for demand builder
    (prep-builder-files builder-inputs-path workbook-recs
                        outputs-path input-map)
    ;;run demand builder
    (plugin/root->demand-file builder-inputs-path)
    ;;create a new m4 workbook, replacing the demand and supply worksheets
    (replace-demand-and-supply base-m4-path (str outputs-path
                                                 "Outputs_DEMAND.txt")
                               supp-demand-path out-path workbook-recs
                               default-rc-policy
                               set-demand-params
                               (str in-root periods-name)
                               (str in-root parameters-name)
                               input-map)
    out-path
    ))

(defn do-taa
  "Highest level entry point to do a TAA run given a map of input
  values.  This does both the preprocessing and MARATHON runs."
  [{:keys [testing?] :as input-map}]
  (binding [*testing?* testing?]
    (do-taa-runs (preprocess-taa input-map) input-map)))


;;supply: search for parent, child relationship
;;make a set of SRCs. filter that set such that if the parent
;;exists, so does the child.
{:ParentSRC " 01300K000", :ChildSRC "01205K000"}

{:ParentSRC "01300K000", :ChildSRC "01205K000"}

;; (def pc-recs (tbl/copy-records!))
;; (def supply-recs (tbl/copy-records!))

(defn parent-and-child
  "returns those supply SRCs for which a child exists in the supply also."
  [pc-recs supply-recs]
  (let [p-to-cs (->> (map (juxt :ParentSRC :ChildSRC) pc-recs)
                     (reduce (fn [acc [p c]] (if (contains? acc p)
                                               (assoc acc p (conj (acc p) c))
                                               (assoc acc p #{c}))) {}))
        enabled-supply (->> supply-recs
                            (filter (fn [r] (= "TRUE"
                                               (clojure.string/upper-case (:Enabled r)) )))
                            (map (fn [r] (:SRC r)))
                            (into #{}))]
    (for [s enabled-supply] [s (clojure.set/intersection (p-to-cs
                                                          s) enabled-supply)])))

(defn find-children2
  "given a map where the keys are parents and the values are sets
  of children, find all children for each parent."
  [m]
  (for [ [k v] m]
    [k
     ;;v should be a set
     (loop [remaining-children v
            found-children #{}]
       (if (empty? remaining-children)
         found-children
         (recur (reduce clojure.set/union (for [c remaining-children] (if-let [res (m c)]
                                                                        res #{})))
                (clojure.set/union found-children remaining-children))))]))      

