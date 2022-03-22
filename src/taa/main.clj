(ns taa.main
  (:gen-class :main true)
  )

;;This is the main entry point for this project to get a repl going
;;from the command line.
(defn -main [& args]
  (clojure.main/repl :init (fn [] (require 'taa.core)
                             (in-ns 'taa.core)
                             )))
