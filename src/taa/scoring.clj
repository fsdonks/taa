(ns taa.scoring
  (:require [spork.util.table :as tbl]
            [marathon.analysis.interpolation :as interp]
            [taa.util :as util]))

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
   :Total "Score"})

(defn interpolate
  "Interpolate between known points with the lerper, assessor, and
  maintain the src"
  [[src recs]]
  (->>
   (interp/grid recs :AC-Supply :RC-Supply :Total)
   (map (fn [r] (assoc r :src src)))
   (map (fn [r] (clojure.set/rename-keys r rename)))))

(def default-weights
  {"PreSurge" 0.5
   "Surge" 0.25
   "PostSurge" 0.25
   })

(defn assess [x]
  (cond (>= x 0.95) 3
        (>= x 0.85) 2
        (>= x 0.60) 1
        :else 0))

(defn assess-risk [ assessor score-rec]
  (let [score (score-rec "Score")]
    (assoc score-rec "Risk" (assessor score))))
  
(defn score-results
  "Given random run records  and a map of :phase-name to phase-weight to
  compute that score, along with an assesor function to return a risk
  assessment of that score, returns records of {:AC :RC :Score :Risk},
  interpolating the results between components if necessary."
  [run-recs & {:keys [phase-weights assessor] :or
               {phase-weights default-weights
                assessor assess}}]
  (->> (all-scores run-recs phase-weights)
       (group-by :src)
       (map interpolate)
       (reduce concat)
       (map (partial assess-risk assessor))))

(defn scores->xlsx
  [run-recs out-path {:keys [phase-weights assessor]}]
  ;;Might not always want to spit these risk results
  (if (and assessor phase-weights)
    (->> (score-results run-recs :phase-weights phase-weights
                        :assessor assessor)
         (util/records->xlsx out-path "Sheet1"))))
