(ns tech.v3.datatype.convolve
  "Namespace for implementing various basic convolutions.  Currently only 1d
  convolutions are supported."
  (:require [tech.v3.datatype.base :as dt-base]
            [tech.v3.datatype.array-buffer :as array-buffer]
            [tech.v3.datatype.copy-make-container :as dt-cmc]
            [tech.v3.parallel.for :as pfor])
  (:import [tech.v3.datatype Convolve1D Convolve1D$Mode ArrayHelpers DoubleReader
            Convolve1D$EdgeMode]
           [java.util.function BiFunction]
           [org.apache.commons.math3.distribution NormalDistribution]))


(defn correlate1d
  "Convolve window over data.  Window must be smaller than data.
  Returns result in `:float64` space.  Convolutions outside the valid
  space of data are supplied zeros to match numpy conventions.

  Options:

  * `:mode` - One of :full, :same, or :valid.
  * `:edge-mode` - One of :zero or :clamp.  Clamp clamps the edge value to the start
    or end respectively. Defaults to :zero.
  * `:finalizer` - A java.util.function.DoubleUnaryOperator that performs
  one more operation on the data after summation but before assigned to
  result.
  * `:force-serial` - For serial execution of the convolution.  Unnecessary except
  for profiling and comparison purposes.
  * `:stepsize` - Defaults to 1, this steps across the input data in stepsize
    increments.
```"
  ([data win {:keys [mode finalizer force-serial edge-mode stepsize]
              :or {mode :full
                   edge-mode :zero
                   stepsize 1}}]
   (let [data (dt-cmc/->double-array data)
         win (dt-cmc/->double-array win)]
     (-> (Convolve1D/correlate
          (reify BiFunction
            (apply [this n-elems applier]
              (if force-serial
                (.apply ^BiFunction applier 0 n-elems)
                (pfor/indexed-map-reduce
                 n-elems
                 (fn [start-idx group-len]
                   (.apply ^BiFunction applier start-idx group-len))
                 dorun))))
          data
          win
          (int stepsize)
          finalizer
          (case mode
            :full Convolve1D$Mode/Full
            :same Convolve1D$Mode/Same
            :valid Convolve1D$Mode/Valid)
          (case edge-mode
            :zero Convolve1D$EdgeMode/Zero
            :clamp Convolve1D$EdgeMode/Clamp))
         (array-buffer/array-buffer))))
  ([data win]
   (correlate1d data win nil)))


(defn convolve1d
  "Convolve a window across a signal.  The only difference from correlate is the
  window is reversed and then correlate is called.  See options for correlate, this
  has the same defaults.

  Examples:

```clojure
user> (require '[tech.v3.datatype.convolve :as dt-conv])
nil
user> (dt-conv/convolve1d [1, 2, 3], [0, 1, 0.5])
#array-buffer<float64>[5]
[0.000, 1.000, 2.500, 4.000, 1.500]
user> (dt-conv/convolve1d [1, 2, 3], [0, 1, 0.5] {:mode :same})
#array-buffer<float64>[3]
[1.000, 2.500, 4.000]
user> (dt-conv/convolve1d [1, 2, 3], [0, 1, 0.5] {:mode :valid})
#array-buffer<float64>[1]
[2.500]
```"
  ([data win options]
   (let [win (dt-base/->buffer win)
         n-win (.lsize win)
         n-win-dec (dec n-win)]
     (correlate1d data
                  (reify DoubleReader
                    (lsize [this] n-win)
                    (readDouble [this idx]
                      (.readDouble win (- n-win-dec idx))))
                  options)))
  ([data win]
   (convolve1d data win nil)))


(defn gaussian1d
  "Perform a 1d gaussian convolution.  Options are passed to convolve1d - but the
  default mode is `:same` as opposed to `:full`.  Values are drawn from a 0 centered
  normal distribution with stddev of 1 on the range of -range*sigma,range*sigma at
  window-len increments. Then the window vector is normalized to length 1 and convolved
  over the data using correlate1d.  This means with a range of '2' you are drawing
  from a 0-centered normal distribution 2 standard distributions so the edges will have
  a pre-normalized value of around 0.05.

  Options:

  * `:mode` - defaults to `:same`, see options for correlate1d.
  * `:edge-mode` - defaults to `:clamp`, see options for correlate1d.
  * `:range` - Defaults to 2, values are drawn from (-range, range) with
    window-len increments.
  * `:stepsize` - Defaults to 1.  Increasing this decreases the size of the
    result by a factor of stepsize.  So you can efficiently downsample your data
    by using a gaussian window and increasing stepsize to the desired downsample
    amount."
  ([data window-len {:keys [range mode edge-mode]
                     :or {range 2
                          mode :same
                          edge-mode :clamp}
                     :as options}]
   (let [dist (NormalDistribution.)
         range (double range)
         window-len (long window-len)
         total-range (* 2 (double range))
         increment (/ total-range (dec window-len))
         window (double-array window-len)
         range-start (double (- range))
         sum
         (loop [idx 0
                sum 0.0]
           (let [next-val (+ range-start (* increment idx))
                 next-dist (.density dist next-val)]
             (if (< idx window-len)
               (do
                 (aset window idx next-dist)
                 (recur (unchecked-inc idx)
                        (+ sum (* next-dist next-dist))))
               sum)))
         inv-len (double (if (== sum 0.0)
                           0.0
                           (/ 1.0 (Math/sqrt sum))))]
     ;;Normalize the vector
     (dotimes [idx window-len]
       (ArrayHelpers/accumMul window idx inv-len))
     (correlate1d data window (assoc options :mode mode :edge-mode edge-mode))))
  ([data window-len]
   (gaussian1d data window-len nil)))


(comment

  (convolve1d [1, 2, 3], [0, 1, 0.5])
  (convolve1d [1, 2, 3], [0, 1, 0.5] {:mode :same})
  (convolve1d [1, 2, 3], [0, 1, 0.5] {:mode :valid})
  (convolve1d (range 100), [0, 1, 0.5] {:edge-mode :zero
                                        :stepsize 2})
  )
