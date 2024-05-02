(ns taa.core
  ;;Require both capacity and requirements here so that the user can
  ;;call functions in either from this init namespace.
  (:require [taa [capacity :as capacity]
             [requirements :as requirements]
             [demandanalysis :as analysis]]))

(defn daily-phases
  "Create phases for each day of a longer phase."
  [[phase start end]]
  (for [day (range start (inc end))]
    [(str phase "-day_" day) day day]))
