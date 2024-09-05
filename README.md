# gRPC Eval

A Clojure gRPC client usable completely at runtime, targeting exploration and REPL-driven development.

**Status:** alpha

This client takes in a file descriptor set (Sometimes called a protoset) to determine the rpc methods
available from a gRPC service. Much like gRPC Server Reflection, this allows request and response schemas
to be determined at runtime.

Request and response objects use native Clojure data types, using type coercion and validation rules to ensure
requests conform. This library is built on `io.grpc`, ensuring only valid payloads are sent to the server.

Primary use-cases of this library are for debugging and interactive development. Please consider the performance
tradeoffs before using for production workloads.

## Usage

Basic rpc call example:

```clojure
(require '[grpc-eval.core :as grpc])

(def client
  (grpc/client {:target "localhost:8080"
                :file-descriptor-set "/tmp/echo-service.dsc"}))

(grpc/call client "grpc_eval.EchoService/Echo" {:say "HelloWorld"})
; => {:echo "HelloWorld"}
```

Status, headers, and trailers can be accessed from the response metadata:
```clojure
(meta response)
; => {:status {:code "OK", :description nil},
;     :headers {"grpc-accept-encoding" ["gzip"],
;               "content-type" ["application/grpc"],
;               "grpc-encoding" ["identity"]},
;     :trailers {}}
```

Failed requests will result in an exception: 
```clojure
(try
  (grpc/call client "grpc_eval.EchoService/Error" {:msg "Will return error"})
  (catch ExceptionInfo ex
    ex))
; => #error {
;  :cause gRPC server responded with error status=[INTERNAL] description=[Error description]
;  :data {:trailers {content-type [application/grpc]}, :status {:description Error description, :code INTERNAL}}
;  ... }
```

Headers can be included with the request:
```clojure
(grpc/call client
  {:method "grpc_eval.EchoService/Echo"
   :headers {"text-key" "ascii text value"
             :keyword-key ["multiple values"
                           "can be passed as an iterable"]
             ;; Binary headers must be suffixed with "-bin"
             "key-bin" (byte-array [(byte 0x63) (byte 0x6c) (byte 0x6a)])}}
  {:say "Headers example"})
```

To list all methods found in the file descriptor set:
```clojure
(grpc/methods client)
; => #{"grpc_eval.EchoService/Echo"
;      "grpc_eval.EchoService/Error"}
```

The protobuf source for all above examples is:
```protobuf
syntax = "proto2";
package grpc_eval;

service EchoService {
  rpc Echo (EchoRequest) returns (EchoResponse);
  rpc Error (ErrorRequest) returns (ErrorResponse);
}

message EchoRequest {
  required string say = 1;
}
message EchoResponse {
  required string echo = 1;
}
message ErrorRequest {
  optional string msg = 1;
}
message ErrorResponse {}
```

### Configuration

Client configuration options:
```clojure
(def client
  (grpc/client
    {;; Required, a NameResolver compliant URI, ex: localhost:8080
     :target "localhost:8080"
     ;; Required, path to a file descriptor set.
     ;; The file descriptor set must be self-contained, use the --include_imports protoc flag.
     :file-descriptor-set "/tmp/echo-service.dsc"
     ;; Default false, whether to use a secure TLS connection.
     :use-tls false
     ;; Default 30 seconds, the deadline in milliseconds for requests made to the target.
     :deadline-millis 30000
     ;; Default true, return enums in response objects as keywords for the enum name.
     ;; If false, enum fields will contain the enum number value.  
     :enums-as-keywords true
     ;; Default true, return message fields as keywords.
     ;; If false, returns message fields as strings.
     :response-fields-as-keywords true
     ;; Default false. If true, will include the raw java Status, Message, Headers and Trailers
     ;; values in the metadata of the response object.
     :include-raw-types false
     ;; Default false. If true, proto2 `optional` fields must be set and will throw a validation
     ;; error if the request does not include the field.
     :optional-fields-required false
     }))
```
Certain configuration options can also be supplied at request time:
```clojure
(grpc/call client
           {:method "grpc_eval.EchoService/Echo"
            :deadline-millis 5000
            :enums-as-keywords true
            :response-fields-as-keywords true
            :include-raw-types false
            :optional-fields-required false}
           {:say "Example with configuration"})
```

## Development

The file descriptor set must be manually compiled for the test suite. Get the `protoc` command from the `protodeps`
plugin and use it to compile a self-contained file descriptor set

```
lein protodeps generate -v  | grep :protoc
# set PROTOC_CMD="" to the value of :protoc, then run:
find test/protos -name '*.proto' | xargs $PROTOC_CMD --include_imports --proto_path=test/protos --descriptor_set_out=target/test-file-descriptor-set.dsc
```

Run tests with `lein test`.

## Message Conversion

Returned message form is dependent on configuration.

Fields can be specified by name as either strings or keywords, or by the field number.
```clojure
;; All valid
{"say" "value"}
{:say "value"}
{1 "value"}
```

Numeric values will be coerced to their correct type as long as the number fits
within its bounds, but `int*` values must be either `Integer` or `Long`.
```clojure
;; Valid
{:float 1.5}
{:float (Double/valueOf 5.55)}
{:double 4/5}
{:int32 (Long/valueOf 123)}
{:int64 Long/MAX_VALUE}
;; Invalid
{:float Double/MAX_VALUE}
{:int32 Long/MAX_VALUE}
{:int32 1.0}
```

Enum values can be passed as keyword, string, integer value, or java type:
```clojure
{:enum :VALUE}
{:enum "VALUE"}
{:enum 1}
{:enum TestEnum/VALUE}
```

Repeatable items can be any non-lazy sequence:
```clojure
{:vector ["1" "2" "3"]
 :seq '("I" "II" "III")
 :java-type (List/of 1 2 3)}
```

Booleans must be expressed as their correct type:
```clojure
{:boolean true}
{:boolean false}
```

Bytes can either be a java `byte[]` array or the protobuf `ByteString` class:
```clojure
{:bytes (byte-array [(byte 0x63) (byte 0x6C) (byte 0x6A)])}
{:bytes (com.google.protobuf.ByteString/copyFromUtf8 "clj")}
```

Nested messages can either be a map or the java type:
```clojure
{:nested-message {:field "value"}}
{:nested-message ^Message msg}
```

## Missing Support

Some protobuf features are not supported:
* Protobuf Extensions and `extend`.
* OneOf (`oneof`) fields.
* Groups (`group`) - This is officially deprecated in proto2 and removed from proto3.

Certain gRPC features are either not exposed or supported, including:
* Streaming requests or responses (non-unary)
* Cancelling or terminating a request
* Configuring compression

Please open an issue on github if you want to see support for any missing features. External contributions are welcome!

## Alpha Status

* Add proto3 test coverage.
* Update validation responses to include detailed protobuf type information.
* Support gRPC [server reflection](https://github.com/grpc/grpc/blob/master/doc/server-reflection.md).
* Separate validator and DynamicMessage generation from client, so its more of an A->B->C flow.
* Support passing a target or a channel. Have some internal cache of channel based on target.
* Close the channel on connection failure, by default it will retry forever in a background thread.
