(ns taa.demandanalysis-test
  (:require [clojure.test :refer :all]
            [taa.demandanalysis :as taalysis]
            [taa.requirements-test :as req-test]
            [proc.demandanalysis :as proclysis]
            [spork.util.io :as io]))

(def input-paths [
                  (str  "requirements/testdata-v7-bog.xlsx")
                  (str "requirements/forward_tagged.xlsx")
                  ])
(def input-resources (req-test/as-resources input-paths))

(def verified-peaks1
  (set
   [{:peak 1.0,
    :intervals [[1.0 260.0]],
    :group "All",
    :period "PreSurge"}
   {:peak 0, :intervals [], :group "All", :period "Surge"}
   {:peak 1.0,
    :intervals [[1000.0 1259.0]],
    :group "All",
    :period "PostSurge"}]))

(def verified-peaks2
  (set
   [{:peak 895.0,
    :intervals [[91.0 363.0]],
    :group "All",
    :period "PreSurge"}
   {:peak 2387.0,
    :intervals [[822.0 1022.0]],
    :group "All",
    :period "Surge"}
   {:peak 0, :intervals [], :group "All", :period "PostSurge"}]))

;;This is really a proc test, but I was just checking behavior with
;;inputs in this repo.
(deftest checking-peaks
  (let
      [[peaks-1 peaks-2] (map proclysis/peaks-from input-resources)]
    (is (= (set peaks-1) verified-peaks1)
        "Check if peaks-from is working properly on this simple
case.")
    (is (= (set peaks-2) verified-peaks2)
        "Check if peaks-from is working properly on a more complicated case.")))

(deftest checking-max
  (let [max-map {"a" "requirements/base-testdata-v7.xlsx"
                 "b" (second input-resources)}
        maxes (taalysis/max-demand  max-map "Surge")
        [src [peak demand]] (first
                             (filter (fn [[k v]] (= k "01205K000"))
                                     maxes))
        maxes-path "test-output/max-a_b.xlsx"
        ;;just in case we haven't created the test-output/ folder from
        ;;the capacity-analysis tests yet
        _ (io/make-folders! "test-output/")]
    (is (= demand "a") 
        "For this SRC, b has a peak of 11 and a has a peak of 27, so the max
should be a.")
    (testing "Just checking to make sure we can write out our maxes
  workbook."
      (taalysis/maxes->xlsx! max-map "Surge" "b" maxes-path))))

