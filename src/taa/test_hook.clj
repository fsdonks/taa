(ns test-hook
  (:require [taa.capacity-test]
            [taa.requirements-test]))
(clojure.test/run-tests 'taa.capacity-test)
(clojure.test/run-tests 'taa.requirements-test)
