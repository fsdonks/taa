;;some quick patches for expanding dumbanneal to
;;allow for mutable solutions.
(ns taa.patch
  (:require [spork.opt.dumbanneal]))
(in-ns 'spork.opt.dumbanneal)
;;Defines a simple annealing function with configuration criteria to
;;admit a variety of composed problems.  User can supply their own
;;step functions, cost functions, temperature, iteration, equilibration,
;;acceptance criteria, temperature decay function, etc.  Defaults to
;;geometric decay (0.9) and a boltzmann acceptance criteria (typical
;;of metropolis-hastings, or classical SA).  Assumes a numeric vector
;;by default, with unconstrained ranges.  If user supplies a custom
;;solution representation, they should also define custom cost functions
;;and step functions.

;;we provide an immutable and a mutable path here.
;;for immutable path, we assume step-function is a persistent
;;operation that yields a new function.
;;this allows us to just store prior solutions (e.g. best)
;;and the like.
;;otherwise, we allow caller to provide functions to undo
;;the step taken - step-back, and to clone the solution, copy-solution.
;;This allows us to use the same API on top of an incremental solution
;;mechanism using mutation to more efficiently prosecute the search.

(defn simple-anneal
  [cost-function init-solution &
   {:keys [t0 tmin itermax equilibration accept? init-cost decay step-function
           ranges step-back copy-solution]
    :or {t0 10000000 tmin 0.0000001  itermax 100000  equilibration 1
         accept? ann/boltzmann-accept?  init-cost (cost-function init-solution)
         decay  (ann/geometric-decay 0.9)
         ranges (for [x init-solution] unconstrained)
         step-back     identity
         copy-solution identity}}]
  (assert (coll? init-solution)
          "Solutions must be vectors of at least one element")
  (let [step-function (or step-function (make-step-fn ranges))
        _ (println {:init-cost init-cost})]
    (loop [temp t0
           n 1
           i 0
           converged?   false
           current-sol  init-solution
           current-cost init-cost
           best-sol     (copy-solution init-solution)
           best-cost    init-cost]
    (if converged?
      {:temp temp :n n :i i    :current-solution current-sol
       :best-solution best-sol :best-cost best-cost}
      (let [temp (double (if (= n 0) (decay temp i) temp))
            n    (long (if (= n 0) equilibration n))]
        (if (or (< temp tmin) (> i itermax))
            (recur temp  n i true current-sol current-cost best-sol best-cost) ;exit
            (let [new-sol  (step-function temp current-sol)
                  new-cost (cost-function new-sol)
                  n (dec n)
                  i (inc i)]
              (if (accept? temp current-cost new-cost)
                (recur temp n i converged?
                       new-sol new-cost (if (< new-cost best-cost)
                                          (do (println {:new-best new-cost :old-best best-cost :temp temp :i i :eq n})
                                              (copy-solution new-sol))
                                          best-sol)
                       (min new-cost best-cost))
                (do #_(when (< (rand) 0.3)
                      (println {:rejected true
                                :current-cost current-cost :new-cost new-cost
                                :temp temp :i i :eq n}))
                    (recur temp n i converged? (step-back current-sol)
                           current-cost best-sol best-cost))))))))))
(in-ns 'taa.patch)
