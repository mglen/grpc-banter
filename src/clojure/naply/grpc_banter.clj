(ns naply.grpc-banter
  (:refer-clojure :exclude [methods])
  (:require [naply.grpc-banter.schema :as s])
  (:import (naply.grpc_banter MessageConverter FileDescriptorRegistry Client)))

(def malli-validation true)

(defn get-service-and-method [full-method]
  (let [[service method _extra] (clojure.string/split full-method #"/")]
    (when (or (nil? method) (some? _extra))
      (throw (IllegalArgumentException. "full-method must be in form 'Service/Method'")))
    [service method]))

(defn methods [client]
  (.getAllServicesMethods (:java-client client)))

(defn validate
  "Returns nil on success, a map of keys and errors on failure"
  ([client full-method request]
   (let [[service method] (get-service-and-method full-method)]
     (validate client service method request)))
  ([client service method request]
   (-> (.getRequestProto (:java-client client) service method)
       (s/validate (:config client) request))))

(defn call
  "Calls the gRPC service synchronously, returning the response proto.
  Headers, trailers, and status are included as metadata.

  Errors are returned as runtime exceptions."
  ([client full-method request]
   (let [[service method] (get-service-and-method full-method)]
     (call client service method request)))
  ([client service method request]
   (when malli-validation
     (when-let [errors (validate client service method request)]
       (throw (ex-info "Request proto failed validation"
                       {:service service :method method
                        :request request
                        :errors errors}))))
   (.callMethod (:java-client client) service method request)))


(defn client [config]
  (s/valid-config? config)
  {:java-client (Client/create config)
   :config config})

(comment
  (def fdr (FileDescriptorRegistry/fromFileDescriptorSetFile "target/file_descriptor_set.dsc"))
  (def message (.findMessageTypeByFullName fdr "runtime_grpc.HelloRequest"))
  (def foo (MessageConverter/cljToMessage message {:greeting {:message "foo"}}))

  (def test-client (partial call (client "target/file_descriptor_set.dsc" "localhost:8082")))
  (def response (test-client "runtime_grpc.HelloService"
                             "SayHello"
                             {:greeting {:message "foo" :requiredMessage "bar"}})))

