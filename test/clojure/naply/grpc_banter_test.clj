(ns naply.grpc-banter-test
  (:refer-clojure :exclude [methods])
  (:require [clojure.test :refer :all]
            [naply.grpc-banter :as banter])
  (:import (naply.grpc_banter TestGrpcServer)
           (clojure.lang ExceptionInfo)
           (java.util.regex Pattern)
           (com.google.protobuf ByteString)))

;; Utils
(declare thrown-with-msg?)
(defn- as-pattern [s] (re-pattern (Pattern/quote s)))

;; Constants
(def valid-AllFieldTypesMessage
  {:string  "string value"
   :integer Integer/MAX_VALUE
   :long    Long/MAX_VALUE
   :double  Double/MAX_VALUE
   :float   Float/MAX_VALUE
   :boolean true
   :bytes   (ByteString/copyFrom (byte-array [(byte 0x43) (byte 0x21)]))
   :enum    :FIRST
   :message {:string "foo"}

   :optionalString  "string value"
   :optionalInteger Integer/MAX_VALUE
   :optionalLong    Long/MAX_VALUE
   :optionalDouble  Double/MAX_VALUE
   :optionalFloat   Float/MAX_VALUE
   :optionalBoolean true
   :optionalBytes   (ByteString/copyFrom (byte-array [(byte 0x43) (byte 0x21)]))
   :optionalEnum    :LAST
   :optionalMessage {:string "foo"}

   :repeatedString  ["one" "two" "three"]
   :repeatedInteger [Integer/MIN_VALUE Integer/MAX_VALUE]
   :repeatedLong    [Long/MIN_VALUE Long/MAX_VALUE]
   :repeatedDouble  [Double/MIN_VALUE Double/MAX_VALUE]
   :repeatedFloat   [Float/MIN_VALUE Float/MAX_VALUE]
   :repeatedBoolean [true false true]
   :repeatedBytes   [(ByteString/copyFrom (byte-array [(byte 0x43) (byte 0x21)]))
                     (ByteString/copyFrom (byte-array [(byte 0x41) (byte 0x42) (byte 0x43)]))]
   :repeatedEnum    [:FIRST :DEFAULT :SECOND]
   :repeatedMessage [{:string "foo"} {:string "bar"}]})
(def valid-NestedMessage
  {:outerString "required"
   :inner {:innerString "also required"
           :inner {:doubleInnerString "still required"}}})

;; Fixtures
(def test-client (atom nil))
(use-fixtures
  :once (fn [test-suite]
          (with-open [server (TestGrpcServer/create 0)]
            (reset! test-client (banter/client {:target                   (str "localhost:" (.getPort server))
                                                :file-descriptor-set      "target/test-file-descriptor-set.dsc"
                                                :optional-fields-required true}))
            (println "Running test server on port" (.getPort server))
            (test-suite))))

;; Tests
(deftest methods
  (testing "Returns all fully qualified gRPC service methods"
    (is (= #{"naply.grpc_banter.EchoService/Echo"
             "naply.grpc_banter.EchoService/Error"
             "naply.grpc_banter.EchoService/AllFieldTypesTest"
             "naply.grpc_banter.EchoService/NestedMessageTest"}
           (banter/methods @test-client)))))

(deftest validate
  (testing "Validation passing"
    (are [method message]
      (nil? (banter/validate @test-client method message))

      "naply.grpc_banter.EchoService/Echo"
      {:say "test message"}

      "naply.grpc_banter.EchoService/AllFieldTypesTest"
      valid-AllFieldTypesMessage


      "naply.grpc_banter.EchoService/NestedMessageTest"
      valid-NestedMessage))
  (testing "Validation errors"
    (are [method message error]
      (= error (banter/validate @test-client method message))

      ;; Missing field
      "naply.grpc_banter.EchoService/Echo"
      {}
      {"say" ["field is required"]}

      ;; Field wrong type
      "naply.grpc_banter.EchoService/Echo"
      {:say 12345}
      {"say" ["should be a string"]}

      ;; Extra field
      "naply.grpc_banter.EchoService/Echo"
      {:say "valid"
       :extra "key"}
      {"extra" ["disallowed key"]}


      ;; All field types required
      "naply.grpc_banter.EchoService/AllFieldTypesTest"
      {}
      {"string" ["field is required"]
       "integer" ["field is required"]
       "long" ["field is required"]
       "double" ["field is required"]
       "float" ["field is required"]
       "boolean" ["field is required"]
       "bytes" ["field is required"]
       "enum" ["field is required"]
       "message" ["field is required"]

       "repeatedString" ["field is required"]
       "repeatedInteger" ["field is required"]
       "repeatedLong" ["field is required"]
       "repeatedDouble" ["field is required"]
       "repeatedFloat" ["field is required"]
       "repeatedBoolean" ["field is required"]
       "repeatedBytes" ["field is required"]
       "repeatedEnum" ["field is required"]
       "repeatedMessage" ["field is required"]

       "optionalString" ["field is required"]
       "optionalInteger" ["field is required"]
       "optionalLong" ["field is required"]
       "optionalDouble" ["field is required"]
       "optionalFloat" ["field is required"]
       "optionalBoolean" ["field is required"]
       "optionalBytes" ["field is required"]
       "optionalEnum" ["field is required"]
       "optionalMessage" ["field is required"]}

      ;; All field types must be correct type
      "naply.grpc_banter.EchoService/AllFieldTypesTest"
      {:string  false
       :integer false
       :long    false
       :double  false
       :float   false
       :boolean ""
       :bytes   false
       :enum    false
       :message false

       :optionalString  false
       :optionalInteger false
       :optionalLong    false
       :optionalDouble  false
       :optionalFloat   false
       :optionalBoolean ""
       :optionalBytes   false
       :optionalEnum    false
       :optionalMessage false

       :repeatedString  false
       :repeatedInteger false
       :repeatedLong    false
       :repeatedDouble  false
       :repeatedFloat   false
       :repeatedBoolean false
       :repeatedBytes   false
       :repeatedEnum    false
       :repeatedMessage false}
      {"string" ["should be a string"]
       "integer" ["should be an integer"]
       "long" ["should be an integer"]
       "double" ["should be a float" "should be a double"]
       "float" ["should be a float"]
       "boolean" ["should be a boolean"]
       "bytes" ["Should be a ByteString or byte[] array"]
       "enum" ["should be either DEFAULT, FIRST, SECOND or LAST"
               "should be either 0, 1, 2 or 999"
               "should be either :DEFAULT, :FIRST, :SECOND or :LAST"]
       "message" ["invalid type"]

       "optionalString" ["should be a string"]
       "optionalInteger" ["should be an integer"]
       "optionalLong" ["should be an integer"]
       "optionalDouble" ["should be a float" "should be a double"]
       "optionalFloat" ["should be a float"]
       "optionalBoolean" ["should be a boolean"]
       "optionalBytes" ["Should be a ByteString or byte[] array"]
       "optionalEnum" ["should be either DEFAULT, FIRST, SECOND or LAST"
                       "should be either 0, 1, 2 or 999"
                       "should be either :DEFAULT, :FIRST, :SECOND or :LAST"]
       "optionalMessage" ["invalid type"]

       "repeatedString" ["invalid type"]
       "repeatedInteger" ["invalid type"]
       "repeatedLong" ["invalid type"]
       "repeatedDouble" ["invalid type"]
       "repeatedFloat" ["invalid type"]
       "repeatedBoolean" ["invalid type"]
       "repeatedBytes" ["invalid type"]
       "repeatedEnum" ["invalid type"]
       "repeatedMessage" ["invalid type"]}

      ;; Repeated fields - must have correct inner type
      "naply.grpc_banter.EchoService/AllFieldTypesTest"
      (merge valid-AllFieldTypesMessage
             {:repeatedString  [false]
              :repeatedInteger [false]
              :repeatedLong    [false]
              :repeatedDouble  [false]
              :repeatedFloat   [false]
              :repeatedBoolean [""]
              :repeatedBytes   [false]
              :repeatedEnum    [false]
              :repeatedMessage [false]})
      {"repeatedString" [["should be a string"]]
       "repeatedInteger" [["should be an integer"]]
       "repeatedLong" [["should be an integer"]]
       "repeatedDouble" [["should be a float" "should be a double"]]
       "repeatedFloat" [["should be a float"]]
       "repeatedBoolean" [["should be a boolean"]]
       "repeatedBytes" [["Should be a ByteString or byte[] array"]]
       "repeatedEnum" [["should be either DEFAULT, FIRST, SECOND or LAST"
                        "should be either 0, 1, 2 or 999"
                        "should be either :DEFAULT, :FIRST, :SECOND or :LAST"]]
       "repeatedMessage" [["invalid type"]]}
      
      ;; Nested field required
      "naply.grpc_banter.EchoService/NestedMessageTest"
      {:outerString "foo" :inner {}}
      {"inner" {"innerString" ["field is required"]
                "inner" ["field is required"]}})))

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

  (testing "Successful response [headers]"
    (let [request-headers {"test-key"           "test-value"
                           :keyword             "keyword-value"
                           "list-key"           ["list-val1" "list-val2" "list-val3"]
                           "binary-bin"         (byte-array [(byte 0x63) (byte 0x6c) (byte 0x6a)])
                           "empty-list-ignored" []}
          expected-headers {"test-key"   ["test-value"]
                            "keyword"    ["keyword-value"]
                            "list-key"   ["list-val1" "list-val2" "list-val3"]
                            "binary-bin" [[(byte 0x63) (byte 0x6c) (byte 0x6a)]]}
          response-headers (-> (banter/call @test-client
                                            {:method  "naply.grpc_banter.EchoService/Echo"
                                             :headers request-headers}
                                            {:say "HelloWorld"})
                               meta
                               :headers)]
      (is (= expected-headers
             (-> response-headers
                 ;; Response headers will have extra values, filter keys to only the request headers
                 (select-keys (map #(if (keyword? %) (name %) %) (keys request-headers)))
                 ;; Make the byte-array a vec so that values will be compared instead the array compared by identity
                 (update "binary-bin" #(mapv vec %)))))))

  (testing "Successful response [serialization / deserialization matches]"
    (is (= valid-AllFieldTypesMessage
           (banter/call @test-client
                        "naply.grpc_banter.EchoService/AllFieldTypesTest"
                        valid-AllFieldTypesMessage)))
    (is (= valid-NestedMessage
           (banter/call @test-client
                        "naply.grpc_banter.EchoService/NestedMessageTest"
                        valid-NestedMessage))))

  (testing "Error response [server exception]"
    (try
      (let [resp (banter/call @test-client
                              "naply.grpc_banter.EchoService/Error"
                              {:unused "Gonna fail"})]
        (is (not (any? resp)) (str "Expected exception but got response" resp)))
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
