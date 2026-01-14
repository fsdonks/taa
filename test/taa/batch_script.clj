;;A simplistic script file for HPC scripting on array jobs on
;;PBS or other systems.
(ns taa.batch-script
  (:require [spork.util [io :as io]]
            [taa [core :as core]]))

(defn env [x] (System/getEnv x))
;;assuming we're in a PBS job array submission deal.
(def job-index (env "PBS_ARRAY_INDEX"))

;;just load up a relative file. assuming we're running this from a clojure repl
;;through leiningen in the taa project, the below path is accurate.

;;actual usage needs to change relative to actual path on production system.
(load-file (io/file-path "./test/taa/batch_test.clj"))
;;alias the namespace we just loaded for convenience.
(require '[taa.batch-test :as batch])
;;an alternate simplified assumption: scripts and data are in the cwd.
#_(load-file "batch_test.clj")

;;perform a run for the "AP" designs.
;;assume we have a run plan in cwd/plan.edn:
(defn run-me []
  (core/run-from-plan "plan.edn" job-index batch/input-map-AP))

;;then our bash invocation would be something like.
1  #!/bin/bash
 2  #PBS -l select=1:ncpus=48:mpiprocs=48
 3  #PBS -l walltime=00:20:00
 4  #PBS -A Project_ID
 5  #PBS -q debug
 6  #PBS -N Job_Array_Test
 7  #PBS -J 0-12:3
 8  #PBS -j oe	
 9  #PBS -V
10
11  #
12  #  PBS -J 0-12:3 signifies a job array from 0 to 12 in steps of 3.
13  #
14
15  echo "PBS Job Id PBS_JOBID is ${PBS_JOBID}"
16
17  echo "PBS job array index PBS_ARRAY_INDEX value is ${PBS_ARRAY_INDEX}"
18
19  #
20  #  To isolate the job id number, cut on the character "[" instead of
21  #  ".".  PBS_JOBID might look like "48274[].server" rather "48274.server"
22  #  in job arrays
23  #
24  JOBID=`echo ${PBS_JOBID} | cut -d'[' -f1`
25
26  cd $WORKDIR
27
28  # Make a directory of the main PBS JOBID
29  mkdir ${JOBID}
30
31  # go in said directory
32  cd ${JOBID}
33
34  # Make a subdirectory with the current PBS Job Array Index
35  mkdir ${PBS_ARRAY_INDEX}
36
37  # Make a variable that has full path to this run
38  # TMPD might look like this /p/work1/smith/392813/9/
39  TMPD=${WORKDIR}/${JOBID}/${PBS_ARRAY_INDEX}
40
41  # copy executable or do a module load to get paths to executables
42  cp $WORKDIR/picalc.exe ${TMPD}/picalc.exe
43
44  # Though not used here, OPTIONAL_INPUT could hold various inputs
45  # that have different parameters set
46  OPTIONAL_INPUT=$WORKDIR/input.${PBS_ARRAY_INDEX}
47
48  # cd into directory that will contain all output
49  # from this PBS_ARRAY_INDEX run
50  cd ${TMPD}
51
52  # run job and redirect output
53  mpiexec_mpt -n 48 ./picalc.exe ${OPTIONAL_INPUT}  >& output.o$JOBID
54
55  exit

