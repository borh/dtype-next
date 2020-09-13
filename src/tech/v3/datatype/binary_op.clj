(ns tech.v3.datatype.binary-op
  (:require [tech.v3.datatype.base :as dtype-base]
            [tech.v3.datatype.casting :as casting]
            [tech.v3.datatype.dispatch :as dispatch]
            [tech.v3.datatype.protocols :as dtype-proto]
            [primitive-math :as pmath])
  (:import [tech.v3.datatype LongReader DoubleReader ObjectReader
            BinaryOperator PrimitiveReader
            BinaryOperators$LongBinaryOperator
            BinaryOperators$DoubleBinaryOperator
            BinaryOperators$ObjectBinaryOperator]
           [clojure.lang IFn]))


(set! *warn-on-reflection* true)


(defn ->operator
  (^BinaryOperator [item opname]
   (cond
     (instance? BinaryOperator item)
     item
     (instance? java.util.function.BinaryOperator item)
     (let [^java.util.function.BinaryOperator item item]
       (reify BinaryOperators$ObjectBinaryOperator
         (binaryObject [this lhs rhs]
           (.apply item lhs rhs))))
     (instance? IFn item)
     (let [^IFn item item]
       (reify BinaryOperators$ObjectBinaryOperator
         (binaryObject [this lhs rhs]
           (.invoke item lhs rhs))))))
  (^BinaryOperator [item] (->operator item :_unnamed)))


(defn iterable
  (^Iterable [binary-op res-dtype lhs rhs]
   (let [binary-op (->operator binary-op)
         lhs (dtype-base/ensure-iterable lhs)]
     (cond
       (casting/integer-type? res-dtype)
       (dispatch/typed-map-2 (fn [^long lhs ^long rhs]
                               (.binaryLong binary-op lhs rhs))
                             :int64 lhs rhs)
       (casting/float-type? res-dtype)
       (dispatch/typed-map-2 (fn [^double lhs ^double rhs]
                               (.binaryDouble binary-op lhs rhs))
                             :float64 lhs rhs)
       :else
       (dispatch/typed-map-2 binary-op res-dtype lhs rhs))))
  (^Iterable [binary-op lhs rhs]
   (iterable binary-op (dtype-base/elemwise-datatype lhs) lhs rhs)))

(defn reader
  (^PrimitiveReader [^BinaryOperator binop res-dtype lhs rhs]
   (let [lhs (dtype-base/->reader lhs)
         rhs (dtype-base/->reader rhs)
         n-elems (.lsize lhs)]
     (cond

       (casting/integer-type? res-dtype)
       (reify LongReader
         (lsize [rdr] n-elems)
         (readLong [rdr idx] (.binaryLong binop
                                          (.readLong lhs idx)
                                          (.readLong rhs idx))))
       (casting/float-type? res-dtype)
       (reify DoubleReader
         (lsize [rdr] n-elems)
         (readDouble [rdr idx] (.binaryDouble binop
                                              (.readDouble lhs idx)
                                              (.readLong rhs idx))))
       :else
       (reify ObjectReader
         (lsize [rdr] n-elems)
         (readObject [rdr idx] (.binaryObject binop
                                              (.readObject lhs idx)
                                              (.readObject rhs idx)))))))
  (^PrimitiveReader [binop lhs rhs]
   (reader binop (casting/widest-datatype
                  (dtype-base/elemwise-datatype lhs)
                  (dtype-base/elemwise-datatype rhs))
           lhs rhs)))

(defn- as-number
  ^Number [x] x)


(defmacro make-numeric-object-binary-op
  ([opname scalar-op object-op identity-value]
   `(reify
      dtype-proto/POperator
      (op-name [item#] ~opname)
      BinaryOperator
      (binaryBoolean [this# ~'x ~'y]
        (throw (Exception. (format "Operation %s is not a boolean operation"
                                   ~opname))))
      ;;Use checked casts here
      (binaryByte [this# ~'x ~'y] (byte ~scalar-op))
      (binaryShort [this# ~'x ~'y] (short ~scalar-op))
      (binaryChar [this# ~'x ~'y]
        (let [~'x (unchecked-int ~'x)
              ~'y (unchecked-int ~'y)]
          (char ~scalar-op)))
      (binaryInt [this# ~'x ~'y] ~scalar-op)
      (binaryLong [this# ~'x ~'y] ~scalar-op)
      (binaryFloat [this# ~'x ~'y] ~scalar-op)
      (binaryDouble [this# ~'x ~'y] ~scalar-op)
      (binaryObject [this# ~'x ~'y]
        (let [~'x (as-number ~'x)
              ~'y (as-number ~'y)]
          ~object-op))
      (initialDoubleReductionValue [this] (double ~identity-value))))
  ([opname scalar-op object-op]
   `(make-numeric-object-binary-op ~opname ~scalar-op ~object-op 1)))


(defmacro make-float-double-binary-op
  ([opname scalar-op identity-value]
   `(-> (reify
          dtype-proto/POperator
          (op-name [item#] ~opname)
          BinaryOperator
          (binaryBoolean [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not a boolean operation"
                                       ~opname))))
          ;;Use checked casts here
          (binaryByte [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not a byte operation"
                                       ~opname))))
          (binaryShort [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not a short operation"
                                       ~opname))))
          (binaryChar [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not a char operation"
                                       ~opname))))
          (binaryInt [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not an integer operation"
                                       ~opname))))
          (binaryLong [this# ~'x ~'y]
            (throw (Exception. (format "Operation %s is not an long operation"
                                       ~opname))))
          (binaryFloat [this# ~'x ~'y] ~scalar-op)
          (binaryDouble [this# ~'x ~'y] ~scalar-op)
          (binaryObject [this# ~'x ~'y]
            (.binaryDouble this# (double ~'x) (double ~'y)))
          (initialDoubleReductionValue [this] (double ~identity-value)))
        (vary-meta assoc :operation-space :float32)))
  ([opname scalar-op]
   `(make-float-double-binary-op ~opname ~scalar-op 1.0)))


(defmacro make-int-long-binary-op
  [opname scalar-op]
  `(-> (reify
         dtype-proto/POperator
         (op-name [item#] ~opname)
         BinaryOperator
         (binaryBoolean [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a boolean operation"
                                      ~opname))))
         ;;Use checked casts here
         (binaryByte [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a byte operation"
                                      ~opname))))
         (binaryShort [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a short operation"
                                      ~opname))))
         (binaryChar [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a char operation"
                                      ~opname))))
         (binaryInt [this# ~'x ~'y] ~scalar-op)
         (binaryLong [this# ~'x ~'y] ~scalar-op)
         (binaryFloat [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a float operation"
                                      ~opname))))
         (binaryDouble [this# ~'x ~'y]
           (throw (Exception. (format "Operation %s is not a float operation"
                                      ~opname))))
         (binaryObject [this# ~'x ~'y]
           (.binaryDouble this# (long ~'x) (long ~'y))))
       (vary-meta assoc :operation-space :int32)))



(def builtin-ops
  (->> [(make-numeric-object-binary-op :+ (pmath/+ x y) (+ x y) 0)
        (make-numeric-object-binary-op :- (pmath/- x y) (- x y) 0)
        (make-numeric-object-binary-op :/ (pmath// x y) (/ x y) 1)
        (make-numeric-object-binary-op :* (pmath/* x y) (* x y) 1)
        (make-numeric-object-binary-op :rem (rem x y) (rem x y) 1)
        (make-numeric-object-binary-op :quot (quot x y) (quot x y) 1)
        (make-float-double-binary-op :pow (Math/pow x y) 1)
        (make-numeric-object-binary-op :max (if (pmath/> x y) x y) (if (> x y) x y)
                                       (- Double/MAX_VALUE))
        (make-numeric-object-binary-op :min (if (pmath/> x y) y x) (if (> x y) y x)
                                       Double/MAX_VALUE)
        (make-int-long-binary-op :bit-and (pmath/bit-and x y))
        (make-int-long-binary-op :bit-and-not (bit-and-not x y))
        (make-int-long-binary-op :bit-or (pmath/bit-or x y))
        (make-int-long-binary-op :bit-xor (pmath/bit-xor x y))
        (make-int-long-binary-op :bit-clear (bit-clear x y))
        (make-int-long-binary-op :bit-flip (bit-flip x y))
        (make-int-long-binary-op :bit-test (bit-test x y))
        (make-int-long-binary-op :bit-set (bit-set x y))
        (make-int-long-binary-op :bit-shift-left (pmath/bit-shift-left x y))
        (make-int-long-binary-op :bit-shift-right (pmath/bit-shift-right x y))
        (make-int-long-binary-op :unsigned-bit-shift-right
                                 (unsigned-bit-shift-right x y))
        (make-float-double-binary-op :atan2 (Math/atan2 x y))
        (make-float-double-binary-op :hypot (Math/hypot x y))
        (make-float-double-binary-op :ieee-remainder (Math/IEEEremainder x y))]
       (map #(vector (dtype-proto/op-name %) %))
       (into {})))
