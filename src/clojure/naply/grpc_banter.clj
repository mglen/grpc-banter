(ns naply.grpc-banter
  (:refer-clojure :exclude [methods])
  (:require [naply.grpc-banter.schema :as s])
  (:import (naply.grpc_banter Client)))

(defn- get-service-and-method [{:keys [service method]}]
  (if service
    [service method]
    (let [[_service _method _extra] (clojure.string/split method #"/")]
      (when (or (nil? _method) (some? _extra))
        (throw (IllegalArgumentException. "method must be in form 'package.Service/Method'")))
      [_service _method])))

(defn methods [client]
  (.getAllServicesMethods (:java-client client)))

(defn validate
  "Returns nil on success, a map of keys and errors on failure"
  ([client request message]
   (let [_request (if (string? request) {:method request} request)
         _request (s/decode-request _request (:config client))
         [service method] (get-service-and-method _request)]
     (-> (.getRequestProto (:java-client client) service method)
         (s/validate _request message)))))

(defn call
  "Make a synchronous call the gRPC service, returning the response message
  as a map of fields and values. Headers, trailers, and status are included
  as metadata. Errors are returned as runtime exceptions."
  ([client request message]
   (let [_request (if (string? request) {:method request} request)
         _request (s/decode-request _request (:config client))
         [service method] (get-service-and-method _request)]
     (when-let [errors (-> (.getRequestProto (:java-client client) service method)
                           (s/validate _request message))]
       (throw (ex-info "Request message failed validation"
                       {:request _request
                        :message message
                        :errors  errors})))
     (.callMethod (:java-client client) service method message))))

(defn client [config]
  (let [config (s/decode-client-config config)]
    {:java-client (Client/create config)
     :config config}))

(comment
  (def fdr (FileDescriptorRegistry/fromFileDescriptorSetFile "target/file_descriptor_set.dsc"))
  (def message (.findMessageTypeByFullName fdr "runtime_grpc.HelloRequest"))
  (def foo (MessageConverter/cljToMessage message {:greeting {:message "foo"}}))

  (def test-client (partial call (client "target/file_descriptor_set.dsc" "localhost:8082")))
  (def response (test-client "runtime_grpc.HelloService"
                             "SayHello"
                             {:greeting {:message "foo" :requiredMessage "bar"}})))

