;; The kernel owns this lock. Closing stdin or either process exiting releases it.
(import '(java.nio.channels FileChannel)
        '(java.nio.file Paths StandardOpenOption))

(with-open [writer (FileChannel/open
                    (Paths/get (first *command-line-args*) (make-array String 0))
                    (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
            held (.lock writer)]
  (println "locked")
  (flush)
  (slurp *in*))
