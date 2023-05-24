;;Namespace for
;;Taking an existent full factorial random results.txt dateset and
;;sampling that dataset with various sampling methods
;;Then interpolating results between samples
;;Then comparing the interpolated accuracy vs full factorial runs.
(ns taa.sparse
  (:require [spork.util.table :as tbl]
            [spork.util.excel [docjure :as doc]
             [core :as xl]]                     
            [smiletest.core :as smile]))
