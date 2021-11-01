(ns taa.core
  (:require [spork.util.table :as tbl]
            [spork.util.excel.core :as xl]
            [spork.util.clipboard :as board]))
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
                    :Duration 969
                    :StartDay 731
                    :SRC nil
                    :DemandGroup "RC_NonBOG-War"
                    :OITitle "unnecessary"})

(def idaho-record (assoc nonbog-record
                       :Vignette "Idaho"
                       :Category "NonBOG"
                       :Operation "Idaho"
                       :SourceFirst "NOT-AC-MIN"
                       :Priority 1
                       :DemandGroup "Idaho"))

(defn branch-average [src-unavail]
  (let [groups ( group-by ( fn [[src unavail]] ( subs src 0 2)) src-unavail)]
    ( into {} (map ( fn [ [ group rs] ] 
                     [ group ( float (/ ( reduce + (map second rs) ) 
                                        ( count rs) ) ) ] ) groups) ) ) )

(defn idaho+cannibal-recs 
  [src-rcsupply src-war-idaho src-unavail]
  (let [unavail5 (into {} (map (fn [ [s unavail]] [ (subs s 0 5)
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
                                       0))]]
               ;(cond
                 ;(= diff 0) [(assoc idaho-record :Quantity unavail :SRC src)]
                 
                 ;(< diff 0) [(assoc idaho-record :Quantity (- unavail
                  ;                                          diff) :SRC src)]
                 ;:else [(assoc idaho-record :Quantity (- unavail
                    ;                                   diff) :SRC src)
                       ; (assoc nonbog-record :Quantity diff :SRC
                                        ;     src)]
           [(assoc idaho-record :Quantity (- unavail diff) :SRC src)
            ;;This changed. This year we are assuming that the unavailable
            ;;number cannot be used for idaho.
            (assoc nonbog-record :Quantity unavail :SRC
                   src)]
           )
               (reduce concat)
               (remove (fn [r] (= (:Quantity r) (float 0))))
               (remove (fn [r] (= (:Quantity r) 0))))))
                             
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

(defn get-idaho+cannibal-recs [workbook-recs]
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
                      (reduce (fn [acc {:keys [SRC Idaho]}]
                                (assoc acc SRC Idaho)) {})) ]
    (idaho+cannibal-recs rc-supply src-war-idaho rc-unavail)))
  
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

(def default-policy-name "TAA22-26 RCSurge_Default_Composite")
(defn supply-table
  "Given" [record-map]
  (let [rs (->> (record-map "SupplyDemand")
             (map ( fn [r] ( select-keys r
                                         [:SRC :UNTDS :RA :ARNG :USAR]))))
        tblr (->> (columns->records rs
                                    [:SRC :UNTDS] :Quantity :Component)
               (filter (fn [{:keys [Quantity]}] (and Quantity
                                                     (not (zero? Quantity))))))
        policy-map (->> (record-map "policy_map") 
                     (reduce (fn [acc {:keys [SRC
                                              CompositePolicyName]}]
                               (assoc acc SRC
                                      CompositePolicyName)) {}))
        policy-map5 ( into {} (map ( fn [ [ s policy] ] [ ( subs s 0 5)
                                                         policy]) policy-map))
        final-recs (for [{:keys [Component SRC] :as r} tblr
                         :let [policy (if-let [p (policy-map
                                                   SRC)]
                                        p
                                        (if-let [p (policy-map5
                                                     (subs SRC 0 5))]
                                          p
                                          default-policy-name))]]
                     (assoc
                       (clojure.set/rename-keys (merge other-fields
                                                       r) {:UNTDS :OITitle})
                       :Component (case Component "RA" "AC" "ARNG"
                                    "NG" "USAR" "RC")
                       :Policy (if (= Component "RA") "Auto"
                                   policy) ) ) ]
    (records->string-name-table final-recs)
;(paste-ordered-records! final-recs
;[:Type :Enabled :Quantity :SRC :Component :OITitle :Name
 ;:Behavior :CycleTime :Policy :Tags :Spawntime :Location :Position]
))

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
                                                                  
