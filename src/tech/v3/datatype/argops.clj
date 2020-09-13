(ns tech.v3.datatype.argops
  (:require [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.base :as dtype-base]
            [tech.v3.datatype.binary-pred :as binary-pred]
            [tech.v3.datatype.unary-pred :as unary-pred]
            [tech.v3.datatype.copy-make-container :as dtype-cmc]
            [tech.v3.datatype.list :as dtype-list]
            [tech.v3.datatype.reductions :as reductions]
            [tech.v3.datatype.errors :as errors])
  (:import [it.unimi.dsi.fastutil.bytes ByteArrays ByteComparator]
           [it.unimi.dsi.fastutil.shorts ShortArrays ShortComparator]
           [it.unimi.dsi.fastutil.ints IntArrays IntComparator]
           [it.unimi.dsi.fastutil.longs LongArrays LongComparator]
           [it.unimi.dsi.fastutil.floats FloatArrays FloatComparator]
           [it.unimi.dsi.fastutil.doubles DoubleArrays DoubleComparator]
           [tech.v3.datatype
            Comparators$IntComp
            Comparators$LongComp
            Comparators$FloatComp
            Comparators$DoubleComp
            BinaryPredicate
            PrimitiveList
            IndexReduction]
           [java.util Comparator Arrays List Map]))


(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn ->long-comparator
  ^LongComparator [src-comparator]
  (cond
    (instance? LongComparator src-comparator)
    src-comparator
    (instance? BinaryPredicate src-comparator)
    (.asLongComparator ^BinaryPredicate src-comparator)
    :else
    (let [^Comparator comp (comparator src-comparator)]
      (reify Comparators$LongComp
        (compareLongs [this lhs rhs]
          (.compare comp lhs rhs))))))


(defn ->double-comparator
  ^DoubleComparator [src-comparator]
  (cond
    (instance? DoubleComparator src-comparator)
    src-comparator
    (instance? BinaryPredicate src-comparator)
    (.asDoubleComparator ^BinaryPredicate src-comparator)
    :else
    (let [^Comparator comp (comparator src-comparator)]
      (reify Comparators$DoubleComp
        (compareDoubles [this lhs rhs]
          (.compare comp lhs rhs))))))


(defn ->comparator
  ^Comparator [src-comparator]
  (cond
    (instance? Comparator src-comparator)
    src-comparator
    (instance? BinaryPredicate src-comparator)
    (.asComparator ^BinaryPredicate src-comparator)
    :else
    (comparator src-comparator)))


(defn index-comparator
  [values src-comparator]
  (let [src-dtype (dtype-base/elemwise-datatype values)
        values (dtype-base/ensure-reader values)
        n-values (.lsize values)
        src-comparator (if-let [bin-pred (:binary-predicate (meta src-comparator))]
                         (binary-pred/builtin-ops bin-pred)
                         src-comparator)]
    (if (< n-values Integer/MAX_VALUE)
      (cond
        (casting/integer-type? src-dtype)
        (let [comp (->long-comparator src-comparator)]
          (reify Comparators$IntComp
            (compareInts [this lhs rhs]
              (let [lhs-value (.readLong values lhs)
                    rhs-value (.readLong values rhs)]
                (.compare comp lhs-value rhs-value)))))
        (casting/float-type? src-dtype)
        (let [comp (->double-comparator src-comparator)]
          (reify Comparators$IntComp
            (compareInts [this lhs rhs]
              (let [lhs-value (.readDouble values lhs)
                    rhs-value (.readDouble values rhs)]
                (.compare comp lhs-value rhs-value)))))
        :else
        (let [^Comparator comp (->comparator src-comparator)]
          (reify Comparators$IntComp
            (compareInts [this lhs rhs]
              (let [lhs-value (.readDouble values lhs)
                    rhs-value (.readDouble values rhs)]
                (.compare comp lhs-value rhs-value))))))
      (cond
        (casting/integer-type? src-dtype)
        (let [comp (->long-comparator src-comparator)]
          (reify Comparators$LongComp
            (compareLongs [this lhs rhs]
              (let [lhs-value (.readLong values lhs)
                    rhs-value (.readLong values rhs)]
                (.compare comp lhs-value rhs-value)))))
        (casting/float-type? src-dtype)
        (let [comp (->double-comparator src-comparator)]
          (reify Comparators$LongComp
            (compareLongs [this lhs rhs]
              (let [lhs-value (.readDouble values lhs)
                    rhs-value (.readDouble values rhs)]
                (.compare comp lhs-value rhs-value)))))
        :else
        (let [^Comparator comp (->comparator src-comparator)]
          (reify Comparators$LongComp
            (compareLongs [this lhs rhs]
              (let [lhs-value (.readDouble values lhs)
                    rhs-value (.readDouble values rhs)]
                (.compare comp lhs-value rhs-value)))))))))


(defn argsort
  ([comparator {:keys [parallel?]
                 :or {parallel? true}}
    values]
   (let [n-elems (dtype-base/ecount values)
         comparator (index-comparator values comparator)]
     (cond
       (== n-elems 0)
       (int-array 0)
       (instance? IntComparator comparator)
       (let [^ints idx-ary (dtype-cmc/->array :int32 (range n-elems))]
         (if parallel?
           (IntArrays/parallelQuickSort idx-ary ^IntComparator comparator)
           (IntArrays/quickSort idx-ary ^IntComparator comparator))
         idx-ary)
       :else
       (let [^longs idx-ary (dtype-cmc/->array :int64 (range n-elems))]
         (if parallel?
           (LongArrays/parallelQuickSort idx-ary ^LongComparator comparator)
           (LongArrays/quickSort idx-ary ^LongComparator comparator))
         idx-ary))))
  ([comparator values]
   (argsort comparator {}  values))
  ([values]
   (let [val-dtype (dtype-base/elemwise-datatype values)
         comparator (if (casting/numeric-type? val-dtype)
                      (binary-pred/builtin-ops :<)
                      compare)]
     (argsort comparator {} values))))


(defn argfilter
  [pred options rdr]
  (if-let [rdr (dtype-base/as-reader rdr)]
    (unary-pred/bool-reader->indexes options (unary-pred/reader pred rdr))
    (let [pred (unary-pred/->predicate pred)]
      (->> rdr
           (map-indexed (fn [idx data]
                          (when (.unaryObject pred data) idx)))
           (remove nil?)))))


(defn binary-argfilter
  [pred options lhs rhs]
  (let [lhs (dtype-base/as-reader lhs)
        rhs (dtype-base/as-reader rhs)]
    (if (and lhs rhs)
      (unary-pred/bool-reader->indexes options (binary-pred/reader pred lhs rhs))
      (let [pred (binary-pred/->predicate pred)]
        (map (fn [idx lhs rhs]
               (when (.binaryObject pred lhs rhs) idx))
             (range) lhs rhs)
        (remove nil?)))))


(defn index-reducer
  ^IndexReduction [storage-datatype]
  (reify IndexReduction
    (reduceIndex [this batch-data ctx idx]
      (let [^PrimitiveList ctx (if ctx
                                 ctx
                                 (dtype-list/make-list storage-datatype))]
        (.addLong ctx idx)))
    (reduceReductions [this lhs-ctx rhs-ctx]
      (.addAll ^List lhs-ctx rhs-ctx)
      lhs-ctx)))


(defn arggroup
  "Group by elemens in the reader returning a map of value->list of indexes. Indexes
  may not be ordered."
  (^Map [{:keys [storage-datatype ordered?]
          :or {ordered? true}}
         rdr]
   (let [storage-datatype (or storage-datatype (unary-pred/reader-index-space rdr))]
     (when-not (dtype-base/reader? rdr)
       (errors/throwf "Input must be convertible to a reader"))
     (if ordered?
       (reductions/ordered-group-by-reduce (index-reducer storage-datatype)
                                           nil rdr)
       (reductions/unordered-group-by-reduce (index-reducer storage-datatype)
                                             nil rdr))))
  (^Map [rdr]
   (arggroup nil rdr)))


(defn arggroup-by
  "Group by elemens in the reader returning a map of value->list of indexes. Indexes
  may not be ordered."
  (^Map [partition-fn option rdr]

   (let [storage-datatype (or storage-datatype (unary-pred/reader-index-space rdr))]
     (when-not (dtype-base/reader? rdr)
       (errors/throwf "Input must be convertible to a reader"))
     (if ordered?
       (reductions/ordered-group-by-reduce (index-reducer storage-datatype)
                                           nil rdr)
       (reductions/unordered-group-by-reduce (index-reducer storage-datatype)
                                             nil rdr))))
  (^Map [partition-fn rdr]
   (arggroup-by partition-fn nil rdr)))
