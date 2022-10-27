;;Keeping general utility functions here.
(ns taa.util
  (:require [spork.util.table :as tbl]
            [spork.util.excel.core :as xl]))

(defn records->string-name-table [recs]
  (->> (tbl/records->table recs)
       (tbl/stringify-field-names)
       ))

(defn records->xlsx [wbpath sheetname recs]
  (->> (records->string-name-table recs)
       (xl/table->xlsx wbpath sheetname)
       ))

