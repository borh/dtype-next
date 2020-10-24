(ns tech.v3.tensor.dimensions.global-to-local
  "Given a generic description object, return an interface that can efficiently
  transform indexes in global coordinates mapped to local coordinates."
  (:require [tech.v3.tensor.dimensions.shape :as shape]
            [tech.v3.tensor.dimensions.analytics :as dims-analytics]
            [tech.v3.datatype.index-algebra :as idx-alg]
            [tech.v3.datatype.graal-native :as graal-native]
            [tech.v3.datatype.errors :as errors]
            [primitive-math :as pmath]
            [camel-snake-kebab.core :as csk]
            [clojure.tools.logging :as log])
  (:import [tech.v3.datatype Buffer LongReader LongNDReader]
           [java.util List ArrayList Map HashMap]
           [java.lang.reflect Constructor]
           [java.util.function Function]
           [java.util.concurrent ConcurrentHashMap]))


(set! *unchecked-math* :warn-on-boxed)
(set! *warn-on-reflection* true)


(defn elem-idx->addr-fn
  "Generic implementation of global->local transformation."
  ^Buffer [reduced-dims]
  (let [^objects shape (object-array (:shape reduced-dims))
        ^longs strides (long-array (:strides reduced-dims))
        ^longs offsets (when-not (every? #(== 0 (long %)) (:offsets reduced-dims))
                         (long-array (:offsets reduced-dims)))
        ^longs max-shape (long-array (:shape-ecounts reduced-dims))
        ^longs max-shape-strides (long-array (:shape-ecount-strides reduced-dims))
        n-dims (alength shape)
        n-elems (pmath/* (aget max-shape-strides 0)
                         (aget max-shape 0))]
    ;;With everything typed correctly, this pathway is actually amazingly fast.
    (if offsets
      (reify LongReader
        (lsize [rdr] n-elems)
        (readLong [rdr idx]
          (loop [dim 0
                 result 0]
            (if (< dim n-dims)
              (let [shape-val (aget shape dim)
                    offset (aget offsets dim)
                    idx (pmath/+
                         (pmath// idx (aget max-shape-strides dim))
                         offset)
                    stride (aget strides dim)
                    local-val (if (number? shape-val)
                                (-> (pmath/rem idx (long shape-val))
                                    (pmath/* stride))
                                (-> (.readLong ^Buffer shape-val
                                               (pmath/rem idx
                                                          (.lsize ^Buffer shape-val)))
                                    (pmath/* stride)))]
                (recur (pmath/inc dim) (pmath/+ result local-val)))
              result))))
      (reify LongReader
        (lsize [rdr] n-elems)
        (readLong [rdr idx]
          (loop [dim 0
                 result 0]
            (if (< dim n-dims)
              (let [shape-val (aget shape dim)
                    idx (pmath// idx (aget max-shape-strides dim))
                    stride (aget strides dim)
                    local-val (if (number? shape-val)
                                (-> (pmath/rem idx (long shape-val))
                                    (pmath/* stride))
                                (-> (.readLong ^Buffer shape-val
                                               (pmath/rem idx
                                                          (.lsize ^Buffer shape-val)))
                                    (pmath/* stride)))]
                (recur (pmath/inc dim) (pmath/+ result local-val)))
              result)))))))


(defn reduced-dims->signature
  ([{:keys [shape strides offsets shape-ecounts shape-ecount-strides]} broadcast?]
   (let [n-dims (count shape)
         direct-vec (mapv idx-alg/direct-reader? shape)
         offsets? (boolean (some #(not= 0 %) offsets))
         trivial-last-stride? (== 1 (long (.get ^List strides (dec n-dims))))]
     {:n-dims n-dims
      :direct-vec direct-vec
      :offsets? offsets?
      :broadcast? broadcast?
      :trivial-last-stride? trivial-last-stride?}))
  ([reduced-dims]
   (reduced-dims->signature reduced-dims
                            (dims-analytics/are-reduced-dims-bcast?
                             reduced-dims))))


(defonce ^ConcurrentHashMap defined-classes (ConcurrentHashMap.))

(defonce sig->constructor-fn
  (graal-native/if-defined-graal-native
   (constantly elem-idx->addr-fn)
   (try
     (let [insn-fn
           (requiring-resolve 'tech.v3.tensor.dimensions.gtol-insn/generate-constructor)]
       (fn [signature]
         (try
           (insn-fn signature)
           (catch Throwable e
             (log/warnf e "Index function generation failed for sig %s" signature)
             (constantly elem-idx->addr-fn)))))
     (catch Throwable e
       (log/warn e "insn unavailable-falling back to default indexing system")
       (constantly elem-idx->addr-fn)))))


(defn- absent-sig-fn
  [signature]
  (reify Function
    (apply [this signature]
      (sig->constructor-fn signature))))


(defn make-indexing-obj
  [reduced-dims broadcast?]
  (let [signature (reduced-dims->signature reduced-dims broadcast?)
        reader-constructor-fn
        (or (.get defined-classes signature)
            (.computeIfAbsent
             defined-classes
             signature
             (absent-sig-fn signature)))]
    (reader-constructor-fn reduced-dims)))


(defn get-or-create-reader
  (^Buffer [reduced-dims broadcast? force-default-reader?]
   (let [n-dims (count (:shape reduced-dims))]
     (if (and (not force-default-reader?)
              (<= n-dims 4))
       (make-indexing-obj reduced-dims broadcast?)
       (elem-idx->addr-fn reduced-dims))))
  (^Buffer [reduced-dims]
   (get-or-create-reader reduced-dims
                         (dims-analytics/are-reduced-dims-bcast? reduced-dims)
                         false)))


(defn dims->global->local-reader
  ^Buffer [dims]
  (-> (dims-analytics/dims->reduced-dims dims)
      (get-or-create-reader)))


(defn reduced-dims->global->local-reader
  ^Buffer [reduced-dims]
  (get-or-create-reader reduced-dims))


(defn dims->global->local
  ^LongNDReader [{:keys [reduced-dims] :as dims}]
  (let [shape-ecounts (long-array (:shape-ecounts dims))
        shape-ecount-strides (long-array (:shape-ecount-strides dims))
        n-dims (alength shape-ecount-strides)
        n-dims-dec (dec n-dims)
        n-dims-dec-1 (max 0 (dec n-dims-dec))
        n-dims-dec-2 (max 0 (dec n-dims-dec-1))
        elemwise-reader (dims->global->local-reader dims)
        n-elems (.lsize elemwise-reader)

        ;;Bounds checking
        max-height (aget shape-ecounts n-dims-dec-2)
        max-row (aget shape-ecounts n-dims-dec-1)
        max-col (aget shape-ecounts n-dims-dec)

        ;;xyz->global row major index calculation
        shape-ecount-strides-dec-1 (aget shape-ecount-strides n-dims-dec-1)
        shape-ecount-strides-dec-2 (aget shape-ecount-strides n-dims-dec-2)
        rank n-dims
        outermostDim (long (first shape-ecounts))]
    (reify LongNDReader
      (shape [rdr] (:shape-ecounts dims))
      (lsize [rdr] n-elems)
      (rank [rdr] rank)
      (outermostDim [rdr] outermostDim)
      (readLong [rdr idx]
        (.readLong elemwise-reader idx))
      (ndReadLong [rdr idx]
        (when-not (== n-dims 1)
          (errors/throw-index-out-of-boundsf "Dimension error. Tensor is %d dimensional" n-dims))
        (.readLong elemwise-reader idx))
      (ndReadLong[this row col]
        (when-not (== n-dims 2)
          (errors/throw-index-out-of-boundsf "Dimension error. Tensor is %d dimensional" n-dims))
        (when (or (pmath/>= col max-col)
                  (pmath/>= row max-row))
          (errors/throw-index-out-of-boundsf "read2d - One of arguments %s out of ranged %s"
                                             [row col]
                                             [max-row max-col]))

        (.readLong elemwise-reader
                   (pmath/+ (pmath/* row shape-ecount-strides-dec-1)
                            col)))
      (ndReadLong [this height width chan]
        (when-not (== n-dims 3)
          (errors/throw-index-out-of-boundsf "Dimension error. Tensor is %d dimensional" n-dims))
        (when (or (pmath/>= chan max-col)
                  (pmath/>= width max-row)
                  (pmath/>= height max-height))
          (errors/throw-index-out-of-boundsf "read3d - Arguments out of range - %s > %s"
                                             [max-height max-row max-col]
                                             [height width chan]))
        (.readLong elemwise-reader
                   (pmath/+
                    (pmath/* height shape-ecount-strides-dec-2)
                    (pmath/* width shape-ecount-strides-dec-1)
                    chan)))
      (ndReadLongIter [this dims]
        (let [iter (.iterator dims)]
          (.readLong elemwise-reader
                     (loop [continue? (.hasNext iter)
                            val 0
                            idx 0]
                       (if continue?
                         (do
                           (when-not (< idx rank)
                             (errors/throw-index-out-of-boundsf "Dimension error. Tensor is %d dimensional"
                                                                n-dims))
                           (let [next-val (long (.next iter))]
                             (recur (.hasNext iter)
                                    (-> (* next-val (aget shape-ecount-strides idx))
                                        (pmath/+ val))
                                    (pmath/inc idx))))
                         val))))))))

(def builtin-signatures
  [{:n-dims 3,
    :direct-vec [false false true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true true],
    :offsets? false,
    :broadcast? true,
    :trivial-last-stride? false}
   {:n-dims 2,
    :direct-vec [true true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 1,
    :direct-vec [true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? false}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? false,
    :broadcast? true,
    :trivial-last-stride? false}
   {:n-dims 2,
    :direct-vec [true true],
    :offsets? true,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true true],
    :offsets? false,
    :broadcast? true,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? true,
    :broadcast? true,
    :trivial-last-stride? false}
   {:n-dims 1,
    :direct-vec [true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? false,
    :broadcast? true,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? false}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? true,
    :broadcast? true,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [false true],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? true}
   {:n-dims 2,
    :direct-vec [true false],
    :offsets? false,
    :broadcast? false,
    :trivial-last-stride? false}])

;;Implement builtin signatures that we always want to have
(doseq [sig builtin-signatures]
  (.computeIfAbsent defined-classes sig (absent-sig-fn sig)))


(comment
  (require '[tech.v3.tensor.dimensions :as dims])

  ;;Image dimensions when you have a 2048x2048 image and you
  ;;want to crop a 256x256 sub-image out of it.
  (def src-dims (-> (dims/dimensions [2 4 4] [32 4 1])
                    (dims/rotate [0 0 1])
                    (dims/broadcast [4 4 4])))
  (def reduced-dims (dims-analytics/reduce-dimensionality src-dims))

  (def test-ast (global->local-ast reduced-dims))

  (def class-def (gen-ast-class-def test-ast))

  (def class-obj (insn/define class-def))

  (def first-constructor (first (.getDeclaredConstructors class-obj)))

  (def cargs (reduced-dims->constructor-args reduced-dims))

  (def idx-obj (.newInstance first-constructor cargs))



  ;;Due to striding, there is a discontinuity at index 1024
  (def indexes (map idx-obj (range 1020 1030)))

  )
