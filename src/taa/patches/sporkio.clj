(ns taa.patches.sporkio
  (:require spork.util.io))

(in-ns 'spork.util.io)
;;Since we are using drive names intead of drive letters, we use this
;;quick workaround for now.
(defn dedupe-separators [s] s)
