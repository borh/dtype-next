(defproject cnuernber/dtype-next "8.13-SNAPSHOT"
  :description "A Clojure library designed to aid in the implementation of high performance algorithms and systems."
  :url "http://github.com/cnuernber/dtype-next"
  :license {:name "EPL-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure              "1.10.3" :scope "provided"]
                 [org.ow2.asm/asm                  "9.0"]
                 [insn                             "0.5.2"]
                 [camel-snake-kebab                "0.4.2"]
                 [primitive-math                   "0.1.6"]
                 [it.unimi.dsi/fastutil            "8.2.1"]
                 [org.xerial.larray/larray-mmap    "0.4.1"]
                 [org.apache.commons/commons-math3 "3.6.1"]
                 [org.roaringbitmap/RoaringBitmap  "0.9.0"]
                 [com.github.wendykierp/JTransforms "3.1"]
                 [techascent/tech.resource         "5.04"]
                 [techascent/tech.jna "4.05" :scope "provided"]]
  :java-source-paths ["java"]
  :source-paths ["src"]
  :profiles {:dev
             {:dependencies [[criterium "0.4.5"]
                             [uncomplicate/neanderthal "0.35.0"]
                             [com.taoensso/nippy "3.1.1"]
                             [ch.qos.logback/logback-classic "1.1.3"]
                             [com.clojure-goes-fast/clj-memory-meter "0.1.0"]
                             [techascent/tech.viz "6.00-beta-16-1"]]
              :test-paths ["neanderthal" "test"]}
             :jdk-16 {:jvm-opts ["--add-modules" "jdk.incubator.foreign"
                                 "-Dforeign.restricted=permit"
                                 "--add-opens" "java.base/java.lang=ALL-UNNAMED"
                                 "-Djdk.attach.allowAttachSelf"]}
             :codox
             {:dependencies [[codox-theme-rdash "0.1.2"]
                             [codox "0.10.7"
                              :exclusions [org.ow2.asm/asm-all]]]
              :codox {:project {:name "dtype-next"}
                      :metadata {:doc/format :markdown}
                      :themes [:rdash]
                      :source-paths ["src"]
                      :output-path "docs"
                      :doc-paths ["topics"]
                      :source-uri "https://github.com/cnuernber/dtype-next/blob/master/{filepath}#L{line}"
                      :namespaces [tech.v3.datatype tech.v3.datatype.functional
                                   tech.v3.datatype.errors
                                   tech.v3.tensor
                                   tech.v3.datatype.argops
                                   tech.v3.datatype.list
                                   tech.v3.datatype.bitmap
                                   tech.v3.datatype.datetime
                                   tech.v3.datatype.mmap
                                   tech.v3.datatype.convolve
                                   tech.v3.datatype.wavelet
                                   tech.v3.datatype.gradient
                                   tech.v3.datatype.mmap-writer
                                   tech.v3.datatype.native-buffer
                                   tech.v3.datatype.sampling
                                   tech.v3.datatype.nippy
                                   tech.v3.datatype.rolling
                                   tech.v3.datatype.jna
                                   tech.v3.datatype.ffi
                                   tech.v3.datatype.ffi.clang
                                   tech.v3.datatype.struct
                                   tech.v3.tensor.dimensions
                                   tech.v3.tensor.color-gradients
                                   tech.v3.datatype.reductions
                                   tech.v3.datatype.packing
                                   tech.v3.datatype.ffi.graalvm
                                   tech.v3.parallel.for
                                   tech.v3.libs.buffered-image
                                   tech.v3.libs.neanderthal]}
              :clean-targets [:target-path "docs"]}
             :travis
             {:dependencies [[uncomplicate/neanderthal "0.35.0"]
                             [com.taoensso/nippy "3.1.1"]
                             [ch.qos.logback/logback-classic "1.1.3"]]
              :test-paths ["test"]}
             :uberjar
             {:aot [tech.v3.datatype.main tech.v3.datatype.expose-fn]
              :source-paths ["src" "native_test" "generated_classes"]
              :jvm-opts ["-Dclojure.compiler.direct-linking=true" "-Dtech.v3.datatype.graal-native=true"]
              :uberjar-name "dtype-next.jar"
              :main tech.v3.datatype.main}}
  :aliases {"codox" ["with-profile" "codox,dev" "run" "-m" "tech.v3.libs.lein-codox"]})
