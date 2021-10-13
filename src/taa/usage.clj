;;delete all worksheets except for src baseline and supplydemand.  maybe
;;need new FORGE

;; no matching clause exceptions might be for N/As in excel formulas when reading workbooks into clojure. Change these values to something else, like "na".

;; Move out vignettes to a "vignettes" worksheet (exclude Idaho and Colorado)

;; These instructions are meant for the taa Clojure project.
;;load this from marathon:

(load-file "/home/craig/workspace/taa/src/taa/core.clj")
(ns taa2327)
;;override the workbook path in taa2327 to point to the supply demand
;;file
(def wbpath
  "/home/craig/workspace/taa/resources/SupplyDemand_input.xlsx")
;;need to override the tables as well
(def tbls (xl/wb->tables  (xl/as-workbook wbpath)))

(paste-hld+cannibal! tbls) ;will give records for hld and cannibalized records.
;still got an error because some values aren't a number..... turn stuff to 0s or delete.

(get-vignettes tbls) ;will return the vignettes for demand builder

(supply-records2226 tbls)
;supply-records2226 will return the supply records

Now, to run demand_builder:

;;no input map is needed
(require 'demand_builder.m4plugin)
(ns demand_builder.m4plugin)
(def t2337 "K:\\Divisions\\FS\\_Study Files\\TAA 23-27\\Inputs\\Demand_Builder\\")
(root->demand-file t2337)
;;this might throw an error because of the SRC_By_Day newline errors, but necessary to set up files in Ouputs/ (although it might not...)
;;error is NullPointerException java.util.regex.Matcher.getTextLength (:-1)

;(make sure strength has number! if not, set to 0)
;no strength for the following SRCs:

;;open each Forge file and save SRC_By_Day sheet as tab delimited in Outputs/
(require 'demand_builder.formatter)
(ns demand_builder.formatter)
(root->demandfile (str demand_builder.m4plugin/t2337 "Outputs/"))

;;use post-process-demand function to do this (copy demandrecords first)

;now, change priorities according to the parameters word document
;copy vignette to demandgroup

(capacity-analysis "somepath/m4book-2327.xlsx")

marathon.analysis.random
(comment
;;way to invoke function
(def path "somepath/m4book-2327.xlsx")
(def proj (a/load-project path))
(def phases [["comp" 1 821] ["phase-1" 822 854] ["phase-2" 855 974] ["phase-3" 975 1022] ["phase-4" 1023 1789]])
(def cuts_1 (rand-runs proj 16 phases 0 1.5))
(def cuts_2 (rand-runs proj 16 phases 0 1.5))
)
