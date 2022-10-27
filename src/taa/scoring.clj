(ns taa.scoring
  (:require [spork.util.table :as tbl]
            [smiletest.core :as smile]))

(defn total-fill
  "Sum the total fill across components."
  [{:keys [AC-fill RC-fill NG-fill AC NG RC] :as r}]
  (if (= (+ AC NG RC) 0)
    (assoc r :total-fill 0)
    (assoc r :total-fill (+ AC-fill RC-fill NG-fill))))

(defn total-demand
  "Return the quantity of the demand or return 1 if the demand is 0 to
  avoid division by 0."
    [{:keys [total-quantity AC NG RC] :as r}]
  (if (= total-quantity 0)
    (assoc r :total-demand 1)
    (assoc r :total-demand total-quantity)))

(defn demand-met
  "Calculate the percentage of demand met."
  [recs]
  (/ (reduce + (map :total-fill recs))
     (reduce + (map :total-demand recs))))

(defn weighted-fill
  "Compute the weighted fill according to phase-weights."
  [phase-weights [phase percent]]
  (if-let [weight (phase-weights phase)]
    (* weight percent)
    (throw (Exception. (str "No weight for this phase: "
                            phase)))))
(defn compute-score
  "Compute the weighted score based on phase-weights for each phase."
  [phase-weights [[src ra rc ng]  phase-results]]
  (let [score   (->> phase-results
                     (map (partial weighted-fill phase-weights))
                     (reduce +))]
    {:src src
     :AC-Supply ra
     :RC-Supply rc
     :Total score}))

(defn all-scores
  "Given random run records, compute total Score for each [SRC RA RC NG]"
  [recs phase-weights]
  (->> recs
       (map total-fill)
       (map total-demand)
       (group-by (juxt :SRC :AC :RC :NG :phase))
       (map (fn [[k recs]] [k (demand-met recs)]))
       ;;remove the phase from the key
       (map (fn [[k percent]] [(butlast k) {(last k)
                                            percent}]))
       ;;group by the same key but without the phase
       (group-by first)
       ;;for each key, return a map of phase to percentage met
       (map (fn [[k recs]] [k (reduce merge (map second recs))]))
       (map (partial compute-score phase-weights))))

(def rename
  {:AC-Supply "RA"
   :RC-Supply "RC"
   :src "SRC"
   :color "Risk"
   :Total "Score"})

(def tatom (atom nil))

(defn interpolate
  "Interpolate between known points with the lerper, assessor, and
  maintain the src"
  [lerper assessor [src recs]]
  (->>
   (smile/interpolate-data lerper (tbl/records->table recs)
                           :assessor assessor)
   (map (fn [r] (assoc r :src src)))
   (map (fn [r] (clojure.set/rename-keys r rename)))))

(def default-weights
  {"PreSurge" 0.5
   "Surge" 0.25
   "PostSurge" 0.25
   })

(defn score-results
  "Given random run records, an assessing function to compute a
  :Risk based on Score, and a map of :phase-name to phase-weight to
  compute that score, returns records of {:AC :RC :Score :Risk},
  interpolating the results between components if necessary with a
  lerper defined in the smile namespace."
  [run-recs & {:keys [assessor phase-weights lerper] :or
              {assessor smile/assess
               phase-weights default-weights
               lerper smile/->shepard}}]
  (->> (all-scores run-recs phase-weights)
       (group-by :src)
       (map (partial interpolate lerper assessor))
       (reduce concat)))
       
  

  
  
