(ns naply.grpc-banter
  (:refer-clojure :exclude [methods])
  (:require [naply.grpc-banter.schema :as s]
            [naply.grpc-banter.converter :as c])
  (:import (naply.grpc_banter Client FileDescriptorRegistry)
           (io.grpc StatusRuntimeException)))

(defn- get-service-and-method
  "Validate and provide the inputted service and method"
  [{:keys [service method]}]
  (if service
    [service method]
    (let [[_service _method _extra] (clojure.string/split method #"/")]
      (when (or (nil? _method) (some? _extra))
        (throw (IllegalArgumentException.
                 (str "method must be in form 'package.Service/Method' but was " method))))
      [_service _method])))

(defn get-method-descriptor [{:keys [registry]} request]
  (let [[service method] (get-service-and-method request)
        service-descriptor (.findServiceByName registry service)
        method-descriptor (.findMethodByName service-descriptor method)]
    (when-not method-descriptor
      (throw (RuntimeException. (format "Method=[%s] not found in service=[%s]" method service))))
    method-descriptor))

(defn methods
  "Return all grpc methods discovered in the file descriptor set."
  [client]
  (.getAllServiceMethods (:registry client)))

(defn validate
  "Validate the message matches the request schema of the grpc request.
  Returns nil on success, a map of message fields and their errors on failure."
  ([client request message]
   (let [_request (if (string? request) {:method request} request)
         _request (s/decode-request _request (:config client))
         method-descriptor (get-method-descriptor client _request)
         request-message-type (.getInputType method-descriptor)]
     (s/validate request-message-type _request message))))

(defn call
  "Execute a synchronous call the gRPC service, returning the response message
  as a map of fields and values. Headers, trailers, and status are included
  as metadata. Errors are returned as runtime exceptions."
  ([client request message]
   (let [request (if (string? request) {:method request} request)
         request (s/decode-request request (:config client))
         method-descriptor (get-method-descriptor client request)
         request-message-type (.getInputType method-descriptor)
         response-message-type (.getOutputType method-descriptor)]
     (when-let [errors (s/validate request-message-type request message)]
       (throw (ex-info "Request message failed validation"
                       {:request request
                        :message message
                        :errors  errors})))
     (try
       (c/RpcResponse->clj
         request
         (.callMethod (:java-client client)
                      method-descriptor
                      (c/clj->Message request message request-message-type)
                      (c/clj->Metadata (:headers request))
                      (:deadline-millis request))
         response-message-type)
       (catch StatusRuntimeException e
         (throw (c/StatusRuntimeException->exception-info request e)))))))

(defn client
  "Creates and returns a grpc-banter client."
  [config]
  (let [config (s/decode-client-config config)]
    {:java-client (Client/create (:target config))
     :registry    (FileDescriptorRegistry/fromFileDescriptorSet
                    ^String (:file-descriptor-set config))
     :config      config}))

(comment

  (def test-client
    (client {:target "localhost:8006"
             :file-descriptor-set "target/test-file-descriptor-set.dsc"}))

  (methods test-client)

  (call test-client "naply.grpc_banter.EchoService/Echo" {:say "HelloWorld"})

  )

