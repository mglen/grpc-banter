# gRPC Banter

A runtime Clojure gRPC client.

**Status:** alpha

A gRPC client that takes a file descriptor set to determine the rpc methods available from
a service. Features include request validation and type coercion.

This started as a personal project in order to give myself a better experience interacting with
gRPC services from a repl environment. The focus of this library is on validation, human-readable
errors, and simple types. I cannot recommend it for services where performance and data integrity
are highly important.

## Usage

Basic rpc call example:
```clojure
(require '[naply.grpc-banter :as banter])

(def client
  (banter/client {:target "localhost:8080"
                  :file-descriptor-set "/tmp/echo-service.dsc"}))

(banter/call client "grpc_banter.EchoService/Echo" {:say "HelloWorld"})
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
  (banter/call client "grpc_banter.EchoService/Error" {:unused "Will return error"})
  (catch ExceptionInfo ex
    ex))
; => #error {
;  :cause gRPC server responded with error status=[INTERNAL] description=[Error description]
;  :data {:trailers {content-type [application/grpc]}, :status {:description Error description, :code INTERNAL}}
;  ... }
```

Headers can be included with the request:
```clojure
(banter/call client
  {:method "grpc_banter.EchoService/Echo"
   :headers {"text-key" "ascii text value"
             :keyword-key ["multiple values"
                           "can be passed as an iterable"]
             ;; Binary headers must be suffixed with "-bin"
             "key-bin" (byte-array [(byte 0x63) (byte 0x6c) (byte 0x6a)])}}
  {:say "Headers example"})
```

List all found service methods:
```clojure
(banter/methods client)
; => #{"grpc_banter.EchoService/Echo"
;      "grpc_banter.EchoService/Error"}
```

The protobuf source for all examples is:
```protobuf
package grpc_banter;

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
  optional string unused = 1;
}

message ErrorResponse {
  optional string unused = 1;
}
```

### Configuration

Client configuration options:
```clojure
(def client
  (banter/client
    {;; Required, must be a NameResolver-compliant URI, ex: localhost:8080
     :target "localhost:8080"
     ;; Required, must be a resolvable path to a file descriptor set.
     ;; The file descriptor set must be self-contained, use the --include_imports protoc option.
     :file-descriptor-set "/tmp/echo-service.dsc"
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
(banter/call client
             {:method "grpc_banter.EchoService/Echo"
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
