;;This namespace is used to preprocess TAA inputs and do TAA runs
;;using inputs like those specified in the accompanying usage.clj.
(ns taa.capacity
  (:require [spork.util.table :as tbl]
            [spork.util.clipboard :as board]
            [clojure.java.io :as java.io]
            [spork.util.io :as io]
            [spork.util.excel [docjure :as doc]
             [core :as xl]]
            [demand_builder.m4plugin :as plugin]
            [marathon.analysis.random :as random]
            [marathon.analysis :as a]
            [taa.scoring :as score]
            [taa.util :as util]))

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
                    [ group (/ ( reduce + (map second rs) ) 
                                       ( count rs) ) ] ) groups) ) ) )

(defn get-unavailability
  "Returns a percent of RC unavailable for an SRC if that SRC exists
  in the unavailable map.  Else, look for a five-digit SRC match (this
  should probably be 5-digit average).  Else, look for a branch
  average.  Else, return the average RC unavailability across all
  SRCs."
  [src {:keys [unavails unavails-5 unavails-branch unavail-overall]}]
  (if-let [u (unavails src)]
    u
    (if-let [u (unavails-5 (subs src 0 5))]
      u
      (if-let [u (unavails-branch (subs src 0 2))]
        u
        unavail-overall))))
  
(defn idaho+cannibal-recs 
  [src-rcsupply src-war-idaho src-unavails {:keys [phases
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
                         (= phase-name idaho-end)) phases))]
    (->> (for [[src supply] src-rcsupply
               :let [unavail-percent (get-unavailability src
                                                         src-unavails)
                     ;;rounding availability down, would be Math/ceil here
                     unavail (Math/ceil (* unavail-percent supply))
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

(defn unavailables
  "Given the SupplyDemand worksheet, compute the precent of rc
  unavailable by src inside of a map.  Other values in the map are the
  averages of rc unavailable by five-digit src, by branch, and
  overall."
  [workbook-recs rc-supply]
  (let [available-rc (->> (workbook-recs "SupplyDemand")
                          (reduce (fn [acc {:keys [SRC
                                                   RCAvailable] } ]
                                    (if (number? RCAvailable)
                                      (assoc acc SRC RCAvailable)
                                      acc) ) {}))
         rc-unavail (->> (for [[src supply] rc-supply
                              ;;when we have a available number for the src
                              ;;otherwise, this will defer to
                              ;;idaho+cannibal-recs to find unavailable
                               :when (available-rc src)]
                           ;;new assumption to default to 0.5 here
                           [src (- 1 (if (or (zero? (available-rc src))
                                             (zero? supply)) 0.5
                                        (/ (available-rc src)
                                           supply)
                                        ;;stopped.  should just rescan
                                        )) ])
                        (into {} ) )
        unavail5 (into {} (map (fn [ [s unavail]] [ (subs s 0 5)
                                                   unavail]) rc-unavail))
        averages (branch-average rc-unavail)
        average (/ (reduce + (map second rc-unavail))
                          (count rc-unavail))]
    {:unavails rc-unavail
     :unavails-5 unavail5
     :unavails-branch averages
     :unavail-overall average}))
    
(defn get-idaho+cannibal-recs [workbook-recs rc-supply rc-unavailable {:keys [idaho-name] :as input-map}]
  (let [src-war-idaho (->> (workbook-recs "SupplyDemand")
                           (reduce (fn [acc {:keys [SRC] :as r}]
                                     (assoc acc SRC (get r (keyword idaho-name)))) {})) ]
    (idaho+cannibal-recs rc-supply
                         src-war-idaho
                         rc-unavailable input-map)))


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
       (util/records->xlsx (str out-dir
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

(defn add-quantities
  "Given multiple forward stationed demands, add quantity of each
  forward stationed demand together."
  [supply-demand-rec forward-names]
  (->>
   forward-names
   (map keyword)
   (map supply-demand-rec)
   (filter number?)
   (reduce +)
   (int)))

(defn fwd-demands
  "Given a map of :compo to a set of demand names for that compo,
  return all of the demand names in a set.  Also works if a set is passed
  instead of a map for backward compatability."
  [compo-demands]
  (if (map? compo-demands)
    (reduce clojure.set/union (vals compo-demands))
    compo-demands))

(defn check-forwards
  "Check to see if we only have ac units forward like we used to for
  backwards compatability since now we're extending this to multiple
  compos."
  [forward-names]
  (if (set? forward-names)
    ;;only ac forward
    ;;maintains backwards compatability
    {:ac forward-names}
    forward-names))

(defn forward-reducer
  "Given one set of forward stationed demand names, conj the sum of
  those demand quanties onto the vector of quantities for each compo."
  [r compo-quantities name-set]
  (let [quantity (add-quantities r name-set)]
    (conj compo-quantities
          ;;conjs nil otherwise
          (when (not (zero? quantity))
            quantity))))
            
(defn forward-quantities
  "Returns a map of SRC to the forward stationed quantity."
  [record-map {:keys [forward-names]}]
  (let [{:keys [ac ng rc]} (check-forwards forward-names)]
    (->> (record-map "SupplyDemand")
         (reduce (fn [acc {:keys [SRC] :as r} ]
                   (assoc acc SRC
                          (reduce (partial forward-reducer r)
                                  [] [ac ng rc])))
                 {}))))

(defn tag-forward
  "Returns a key and value string for the SupplyRecord Tags field so
  that we can bin the forward stationed units."
  [forward-num]
  (str ":preprocess [align-units [["
       :forward " " forward-num "]]] "))

(defn tag-unavailable
  "Returns a key and value string for the SupplyRecord Tags field so
  that we can indicate the number of RC units that should be
  cannibalized."
  [percent]
  (str ":rc-unavailable " percent " "))

(defn tag-supply
  "Return a tag for all supply records.  Currently only binning the forward
  stationed units."
  [bin-forward? compo src forward-nums unavailables merge-rc?]
  (let [[ac-forward ng-forward rc-forward] (forward-nums src)
        unavailable (get-unavailability src unavailables)]
    (str
     ;;start of tag
     "{"
    (when (and bin-forward? (= compo "RA") (not (nil? ac-forward)))
      (tag-forward ac-forward))
    ;;only going to use this tag for multiple reps for the RC so they
    ;;would have been merged into one compo.
    (when (and (= compo "USAR") merge-rc?)
      (str (tag-unavailable unavailable)
           (when rc-forward (tag-forward rc-forward))))
    (when (and (= compo "ARNG") ng-forward)
      (tag-forward ng-forward))
    ;;end of tag
    "}"
    )))
  

;;Need 0 quantities for a requirements
;;analysis record if we are binning forward
;;stationed supply.
;;For capacity analysis, 0 supply quantity
;;would be out of scope.
(defn replace-nils
  "Given a collection of fields and a collection of records, if the
  fields are nil, replace them with 0."
  [fields recs]
  (for [r recs]
    (reduce (fn [new-r field] 
              (if (new-r field)
                new-r
                (assoc new-r field 0)))
            r fields)))
  
(defn prep-edta
  "Given a record from a SupplyDemand worksheet, prep the input data
  for the edta supply risk chart"
  [rc-unavailables upper upper-rc min-distance {:keys [RA USAR SRC] :as r} ]
  (let [[low-rc high-rc] (random/bound->bounds USAR [0 upper-rc]
                                               :min-distance min-distance)
        [low-ac high-ac] (random/bound->bounds RA [0 upper]
                                               :min-distance min-distance)]
    (-> r
     (assoc 
      :RC_Available (- 1 (get-unavailability SRC
                                             rc-unavailables))
      :RC high-rc
      :RA high-ac
      :T2 30
      :T3 60)
     (select-keys [:RC :RA :RC_Available :SRC :T2 :T3]) )))
           
                    
(defn merge-rc [merge-rc? rc-unavailables {:keys [upper-rc
                                                  upper
                                                  min-distance]
                                           :or {min-distance 0
                                                upper-rc 1
                                                upper 1}
                                           :as input-map} recs]
  (if merge-rc?
    (let [modified-recs (map (fn [{:keys [ARNG USAR] :as r}]
                               (assoc r :USAR (+ ARNG USAR)))
                             recs)
          edta-recs (map (partial prep-edta rc-unavailables upper
                                  upper-rc min-distance)
                         modified-recs)
          _ (util/records->xlsx
             (util/edta-supply-path input-map)
             "Sheet1"
             edta-recs)]
      modified-recs)
    recs))

(defn supply-table
  "Create the SupplyRecord for m4 from the SupplyDemand worksheet."
  [record-map rc-default-policy rc-unavailables
   {:keys [merge-rc? bin-forward?] :as input-map}]
  (let [constant-cols [:SRC :UNTDS :RA :USAR]
        kept-cols (if merge-rc?
                    constant-cols
                    (conj constant-cols :ARNG))
        rs (->> (record-map "SupplyDemand")
                (replace-nils [:RA :USAR :ARNG])
                (merge-rc merge-rc? rc-unavailables input-map)
                (map ( fn [r] ( select-keys r
                               kept-cols))))
        tblr (columns->records rs
                               [:SRC :UNTDS] :Quantity :Component)
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
                      :Tags  (tag-supply bin-forward? Component SRC
                                         forward-nums
                                         rc-unavailables
                                         (:merge-rc? input-map))))]
    (util/records->string-name-table final-recs)))

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
    (doc/load-workbook-from-resource filepath)
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
(ns spork.util.excel.docjure)
(require '[clojure.string :as string])

(defn read-and-strip
  "Turn a cell into a string with read-cell but also put the cell
  values that have a newline in them in quotes so that it opens as tab
  delimited in Excel properly."
  [c]
  (let [v (read-cell c)]
    (when v
      (clojure.string/replace v #"\n" "")
      )))
       
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
                                                      (read-and-strip c)) %)
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
        rc-supply (->> (workbook-recs "SupplyDemand")
                       (map (fn [{:keys [SRC ARNG USAR]}]
                              [SRC (reduce + (remove nil? [ARNG USAR]))]))
                       (into {}))
        rc-unavailables (unavailables workbook-recs rc-supply)
        demand-table (->> (tbl/tabdelimited->records demand-path)
                          (into [])
                          (concat (get-idaho+cannibal-recs
                                   workbook-recs rc-supply rc-unavailables
                                   input-map))
                          (map set-demand-params)
                          (util/records->string-name-table))
        supply-table (supply-table workbook-recs
                                   default-rc-policy
                                   rc-unavailables
                                   input-map
                                   )
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

(defn conj-in
  "for each value in a map, conj an item x, onto that value."
  [m x]
  (into {}
        (for [[k v] m]
          [k (conj v x)])))

;;A transducer to enable all records when used with xform-records.
(def enabling-fn
  (map (fn [{:keys [Enabled] :as r}]
         (assoc r :Enabled true))))

(defn enable-before-transform
  [trans-map]
  (conj-in trans-map enabling-fn))

(defn filter-srcs
  "Return a transducer that filters (or removes) records where the SRC
  doesn't match."
  [srcs filter?]
  (let [f (if filter? filter remove)]
    (f (fn [{:keys [SRC] :as r}]
         (contains? srcs SRC)))))
                     
(defn supply-src-filter
  [srcs filter?]
  (enable-before-transform
   {:SupplyRecords
    ;;could have multiple transforms here, too.
    ;;They are evaluated from right to left.
    ;;Remove srcs with false
   [(filter-srcs srcs filter?)]
    }))

;;Then run rand-runs on this, saving as Excursion_results.txt
;;then could co-locate a usage.py
(defn do-taa-runs [in-path {:keys [identifier
                                   resources-root
                                   phases
                                   compo-lengths
                                   reps
                                   lower
                                   lower-rc
                                   upper
                                   upper-rc
                                   threads
                                   include-no-demand
                                   seed
                                   transform-proj
                                   min-distance] :or
                            {seed random/+default-seed+
                             lower-rc 1 upper-rc 1
                             min-distance 0} :as input-map}]
  (let [proj (a/load-project in-path)
        proj (-> (if transform-proj
               (a/update-proj-tables transform-proj proj)
               proj)
                  ;;we require rc cannibalization modification for taa
                  ;;but maybe this isn't standard for ac-rc random
                  ;;runs yet
                  (random/add-transform random/adjust-cannibals []))
        results
        (binding [random/*threads* threads]
          (random/rand-runs-ac-rc min-distance lower-rc upper-rc
                                  proj :reps reps :phases phases
                                  :lower lower
                            :upper upper :compo-lengths
                            compo-lengths
                            :seed seed))
        results (if include-no-demand (concat results
                                              (add-no-demand
                                               proj
                                               reps
                                               phases
                                               lower
                                               upper))
                    results)
        out-name (str resources-root "results_" identifier)]
    (random/write-output (str out-name ".txt") results)
    (score/scores->xlsx results (str out-name "_risk.xlsx") input-map)
    ))

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
  (vignettes-to-file (workbook-recs "SupplyDemand") vignettes
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
          (doc/save-forge in-path (str outputs-path "FORGE_SE-"
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
    (let [out-path (preprocess-taa input-map)]
    (do-taa-runs out-path input-map))))


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

