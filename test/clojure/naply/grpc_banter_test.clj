(ns naply.grpc-banter-test
  (:refer-clojure :exclude [methods])
  (:require [clojure.test :refer :all]
            [naply.grpc-banter :as banter])
  (:import (naply.grpc_banter TestGrpcServer)
           (clojure.lang ExceptionInfo)))


(def test-client (atom nil))

(use-fixtures
  :once (fn [f]
          (with-open [server (TestGrpcServer/create 0)]
            (reset! test-client (banter/client {:target (str "localhost:" (.getPort server))
                                                :file-descriptor-set "target/test-file-descriptor-set.dsc"
                                                :optional-fields-required true}))
            (println "Running test server on port" (.getPort server))
            (f))))

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
                               {:message "test message"})))
    (is (nil?
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Test2"
                            {:outerString "outer str"
                             :inner {:innerString "inner str"}}))))
  (testing "Validation errors"
    (is (= {"message" ["field is required"]}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Echo"
                            {})))
    (is (= {"message" ["should be a string"]}
           (banter/validate @test-client
                            "naply.grpc_banter.EchoService/Echo"
                            {:message 12354123})))
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
  (testing "Successful response"
    (is (= {:reply "HelloWorld"}
           (banter/call @test-client
                        "naply.grpc_banter.EchoService/Echo"
                        {:message "HelloWorld"}))))
  (testing "Error response (exception)"
    (try
      (let [resp (banter/call @test-client
                              "naply.grpc_banter.EchoService/Error"
                              {:unused "Gonna fail"})]
        (is (not (any? resp)) "Expected exception but got response"))
      (catch ExceptionInfo ex
        (is (= "gRPC server responded with error status=[INTERNAL] description=[All requests will fail.]" (ex-message ex)))
        (is (= "INTERNAL" (-> ex ex-data :status :code)))))))
