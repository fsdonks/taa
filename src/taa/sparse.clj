;;Namespace for
;;Taking an existent full factorial random results.txt dateset and
;;sampling that dataset with various sampling methods
;;Then interpolating results between samples
;;Then comparing the interpolated accuracy vs full factorial runs.
(ns taa.sparse
  (:require [spork.util.table :as tbl]
            [spork.util.excel [docjure :as doc]
             [core :as xl]]                     
            [smiletest.core :as smile]
            [tablecloth.api :as tc]
            [tech.v3.datatype.functional :as dfn]))
(comment
  (require 'taa.sparse)
  (ns taa.sparse)
  ;;Try loading 255MB DemandTrends into Spork records.
  ;;This is how I usually make rows.
  ;;Results in
  ;;[nREPL] Connection closed unexpectedly (connection broken by
  ;;remote peer)
  (def spork-recs
    (into [] (tbl/tabdelimited->records
              "/home/craig/runs/big_test/demand_by_day.txt")))

  ;;This works though and it's fast.
  (def DS (tc/dataset "/home/craig/runs/big_test/demand_by_day.txt"
                      {:separator "\t" :key-fn keyword}))
  (last (tc/rows DS :as-maps))
  
  )
  
