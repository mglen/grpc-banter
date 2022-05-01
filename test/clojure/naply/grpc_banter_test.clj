(ns naply.grpc-banter-test
  (:refer-clojure :exclude [methods])
  (:require [clojure.test :refer :all]
            [naply.grpc-banter :as banter])
  (:import (naply.grpc_banter TestGrpcServer)
           (clojure.lang ExceptionInfo)
           (java.util.regex Pattern)))

;; Utils
(declare thrown-with-msg?)
(defn- as-pattern [s] (re-pattern (Pattern/quote s)))

(def test-client (atom nil))
(use-fixtures
  :once (fn [test-suite]
          (with-open [server (TestGrpcServer/create 0)]
            (reset! test-client (banter/client {:target                   (str "localhost:" (.getPort server))
                                                :file-descriptor-set      "target/test-file-descriptor-set.dsc"
                                                :optional-fields-required true}))
            (println "Running test server on port" (.getPort server))
            (test-suite))))

(deftest methods
  (testing "Returns all fully qualified gRPC service methods"
    (is (= #{"naply.grpc_banter.EchoService/Echo"
             "naply.grpc_banter.EchoService/Error"
             "naply.grpc_banter.EchoService/Test1"
             "naply.grpc_banter.EchoService/Test2"}
           (banter/methods @test-client)))))

(deftest validate
  (testing "Validation passing"
    (is (nil? (banter/validate @test-client
                               "naply.grpc_banter.EchoService/Echo"
                               {:say "test message"})))
    (is (nil?
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Test2"
                            {:outerString "outer str"
                             :inner {:innerString "inner str"}}))))
  (testing "Validation errors"
    (is (= {"say" ["field is required"]}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Echo"
                            {})))
    (is (= {"say" ["should be a string"]}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Echo"
                            {:say 12354123})))
    (is (=  {"requiredMessage" ["field is required"]
             "repeatedMessage" ["field is required"]
             "integer" ["field is required"],
             "long" ["field is required"],
             "enum" ["field is required"]}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Test1"
                            {})))
    (is (= {"outerString" ["should be a string"]
            "inner" {"innerString" ["field is required"]}}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Test2"
                            {:outerString false
                             :inner {}})))))

(deftest call
  (testing "Successful response [method]"
    (is (= {:echo "HelloWorld"}
           (banter/call @test-client
                        "naply.grpc_banter.EchoService/Echo"
                        {:say "HelloWorld"}))))

  (testing "Successful response [method in map]"
    (is (= {:echo "HelloWorld"}
           (banter/call @test-client
                        {:method "naply.grpc_banter.EchoService/Echo"}
                        {:say "HelloWorld"}))))

  (testing "Successful response [service and method]"
    (is (= {:echo "HelloWorld"}
           (banter/call @test-client
                        {:method "Echo"
                         :service "naply.grpc_banter.EchoService"}
                        {:say "HelloWorld"}))))

  (testing "Error response [server exception]"
    (try
      (let [resp (banter/call @test-client
                              "naply.grpc_banter.EchoService/Error"
                              {:unused "Gonna fail"})]
        (is (not (any? resp)) "Expected exception but got response"))
      (catch ExceptionInfo ex
        (is (= "gRPC server responded with error status=[INTERNAL] description=[All requests will fail.]"
               (ex-message ex)))
        (is (= {:code "INTERNAL" :description "All requests will fail."}
               (-> ex ex-data :status))))))

  (testing "Error response [message validation]"
    (is (thrown-with-msg?
          ExceptionInfo
          (as-pattern "Request message failed validation")
          (banter/call @test-client
                       "naply.grpc_banter.EchoService/Error"
                       {:badfield "Gonna fail"}))))

  (testing "Error response [request validation]"
    (is (thrown-with-msg?
          IllegalArgumentException
          (as-pattern "Errors in configuration {:badfield [\"disallowed key\"]}")
          (banter/call @test-client
                       {:method "naply.grpc_banter.EchoService/Echo"
                        :badfield "does not exist"}
                       {:say "test message"})))))

(deftest client
  (testing "Client configuration error [required field]"
    (is (thrown-with-msg?
          IllegalArgumentException
          (as-pattern "Errors in configuration {:file-descriptor-set [\"missing required key\"]}")
          (banter/client {:target "localhost:NoPort"}))))
  (testing "Client configuration error [incorrect type]"
    (is (thrown-with-msg?
          IllegalArgumentException
          (as-pattern "Errors in configuration {:optional-fields-required [\"should be a boolean\"]}")
          (banter/client {:target "localhost:NoPort"
                          :file-descriptor-set "target/test-file-descriptor-set.dsc"
                          :optional-fields-required "true"})))))
