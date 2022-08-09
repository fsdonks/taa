(require 'marathon.core)
(ns marathon.core)
;;Usually, we set Ghost proportions aggregate to 1 0 0 for all SRCs in SupplyRecords
;;Usually, we set all AC SupplyRecords to False
;;easiest way to accoutn for both demands is  to concatenate both demandrecords for COMFORTER

(load-file
 "/home/craig/workspace/requirements-experiments/requirements-experiments.clj")

(ns requirements-experiments)

(def resource-root
  "/home/craig/workspace/requirements-experiments/resources/")

;;before someone made separate notebooks for stopping after periods, I was going to use,

(def input-paths [
(str resource-root "base-testdata-v7.xlsx")
(str resource-root "testdata-v7-bog.xlsx")
])

;;3 cases: the entire demand, through ph4, and through ph3
(def periods [
"PreSurge"
"Surge" 
;"PostSurge"
])

(def miss-days [0 10 
20 30 60
])


(def out-folder (str resource-root "/comforter_inputs/"))

(def c-fn snt/requirements-contour-pruned)		

;;after we have separate notebooks for stopping after periods, use:
;;this will change to comp2
(def periods ["PostSurge"])


;;use snt/contours as contour-fn
(time (stop-after-periods input-paths periods miss-days out-folder c-fn))
