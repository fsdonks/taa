(ns taa.core-test
  (:require [clojure.test :refer :all]
            [taa.core :as core]
            [taa.capacity :as capacity]
            [taa.capacity-test :as cap-test]))

(defn base-only-runs [book-path {:keys [identifier] :as input-map} threads]
  (capacity/do-taa-runs book-path
    (assoc input-map
      :identifier (str identifier "-base")
      :upper 1
      :lower 1
      :upper-rc 1
      :lower-rc 1
      :min-distance 0
      :threads threads)))

(def input-map-daily
  (assoc cap-test/input-map
         :phases (core/daily-phases ["comp1" 1 273])
         :identifier "daily-comp1"
         :transform-proj
         (capacity/supply-src-filter #{"06465K100"} true)))

;;(base-only-runs 
