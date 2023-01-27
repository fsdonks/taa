(ns taa.demandanalysis-test
  (:require [clojure.test :refer :all]
            [taa.demandanalysis-test :as taalysis]
            [taa.requirements-test :as req-test]
            [proc.demandanalysis :as proclysis]))

(def input-paths [
                  (str  "requirements/testdata-v7-bog.xlsx")
                  (str "requirements/forward_tagged.xlsx")
                  ])
(def input-resources (req-test/as-resources input-paths))

(proclysis/peaks-from (first input-resources))
(proclysis/peaks-from (second input-resources))
