(ns taa.requirements-test
  (:require [clojure.test :refer :all]
            [taa.requirements :as requirements]
            [marathon.analysis.requirements.sensitivity :as snt]
            [clojure.java.io :as java.io]))

;;Usually, we set Ghost proportions aggregate to 1 0 0 for all SRCs in SupplyRecords
;;Usually, we set all AC SupplyRecords to False
;;easiest way to accoutn for both demands is  to concatenate both demandrecords for COMFORTER

(def resource-root
  "requirements/")

;;before someone made separate notebooks for stopping after periods, I was going to use,
(def input-paths [
(str resource-root "base-testdata-v7.xlsx")
(str resource-root "testdata-v7-bog.xlsx")
(str resource-root "forward_tagged.xlsx")
(str resource-root "forward_not-tagged.xlsx")
                  ])

(defn as-resources [paths]
  (map (fn [path] (java.io/resource path)) paths))

(def input-paths (as-resources input-paths))

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
(deftest requirements-check
  (testing "Just checking if highest level taa requirements analysis
function completes."
    (requirements/stop-after-periods input-paths periods miss-days
                                     out-folder c-fn
                                     )))
