(def grpc-version "1.45.0")

(defproject io.naply/grpc-banter "0.2.1-SNAPSHOT"
  :description "A runtime Clojure gRPC client"
  :url "https://github.com/mglen/grpc-banter"
  :license {:name "MIT License"
            :url "https://mit-license.org/"}

  :dependencies
  [[org.clojure/clojure "1.11.0"]
   [io.grpc/grpc-protobuf ~grpc-version]
   [io.grpc/grpc-core ~grpc-version :exclusions [io.grpc/grpc-api]]
   [io.grpc/grpc-netty-shaded ~grpc-version :exclusions [io.grpc/grpc-core]]
   [io.grpc/grpc-stub ~grpc-version]
   [org.slf4j/slf4j-api "1.7.32"]
   [javax.annotation/javax.annotation-api "1.3.2"]
   [metosin/malli "0.8.4"]]

  :source-paths ["src/clojure"]
  :java-source-paths ["src/java"]
  :javac-options ["-Xlint:unchecked"]
  :test-paths ["test/clojure"]

  :plugins
  [[com.appsflyer/lein-protodeps "1.0.3"]]

  :profiles
  {:test
   {:dependencies [[com.gfredericks/test.chuck "0.2.13"]]
    :java-source-paths ["src/java" "test/java" "target/test-gen"]
    :resource-paths ["test/resources"]
    :repl-options {:init-ns naply.grpc-banter-test}}

   :dev
   {:dependencies [[ch.qos.logback/logback-classic "1.2.6"]
                   [nrepl/nrepl "0.9.0"]]}}

  :lein-protodeps {:output-path   "target/test-gen"
                   :proto-version "3.19.2"
                   :grpc-version  ~grpc-version
                   :compile-grpc? true
                   :repos {:foo {:repo-type :filesystem
                                 :config {:path "test"}
                                 :proto-paths ["protos"]
                                 :dependencies [protos]}}}

  ; TODO: Cannot 'clean' until there is a programmatic solution to compiling the file descriptor set
  ;:aliases {"build" ["do" "clean" ["protodeps" "generate"] "test"]}

  :repl-options {:init-ns naply.grpc-banter})
