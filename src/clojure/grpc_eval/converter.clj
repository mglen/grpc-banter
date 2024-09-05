(ns grpc-eval.converter
  (:import (com.google.protobuf
             ByteString
             Descriptors$Descriptor
             Descriptors$EnumValueDescriptor
             Descriptors$EnumDescriptor
             Descriptors$FieldDescriptor
             DynamicMessage
             DynamicMessage$Builder
             Internal$EnumLite
             Message
             MessageLite)
           (java.util Map)
           (io.grpc StatusRuntimeException Status)
           (grpc_eval MessageConverter)
           (grpc_eval.internal RpcResponse)))

(declare Message->clj)
(declare clj->Message)

(defn clj->Metadata [metadata]
  (MessageConverter/cljToMetadata metadata))

(defn Status->clj
  [^Status status]
  {:status {:code (-> status .getCode .name)
            :description (.getDescription status)}})

(defn parser-for-field [config ^Descriptors$FieldDescriptor field-desc]
  (case (.name (.getJavaType field-desc))
    ;; Directly convert primitive types
    ("INT" "LONG" "FLOAT" "DOUBLE" "STRING" "BOOLEAN" "BYTE_STRING")
    identity

    ;; Decode enum based on config
    "ENUM"
    (if (:enums-as-keywords config)
      (fn [^Descriptors$EnumValueDescriptor enum] (keyword (.getName enum)))
      (fn [^Descriptors$EnumValueDescriptor enum] (.getName enum)))

    ;; Recurse through messages
    "MESSAGE"
    (fn [^Message msg] (Message->clj config msg (.getDescriptorForType msg)))

    (throw (RuntimeException. (str "Unsupported field type=[" (.getJavaType field-desc) "]")))))

(defn field-name [config ^Descriptors$FieldDescriptor f-desc]
  (if (:response-fields-as-keywords config)
    (keyword (.getName f-desc))
    (.getName f-desc)))

(defn field->clj
  "Return the field value for the field on a message.
  Returns nil if the field is optional and not defined."
  [config
   ^Message message
   ^Descriptors$FieldDescriptor f-desc]
  (let [parse-fn (parser-for-field config f-desc)]
    (cond
      (.isRequired f-desc) (parse-fn (.getField message f-desc))
      (.isOptional f-desc) (when (.hasField message f-desc)
                             (parse-fn (.getField message f-desc)))
      (.isRepeated f-desc) (mapv parse-fn (.getField message f-desc))
      :else (throw (RuntimeException.
                     (format "Found field=[%s] label=[%s] that is neither optional, required, repeated."
                             (.getFullName f-desc) (-> f-desc .toProto .getLabel)))))))

(defn Message->clj
  "Convert a Protobuf message to a clojure map of fields and values."
  [config
   ^Message message
   ^Descriptors$Descriptor message-type]
  (persistent!
    (reduce
      (fn [b field-descriptor]
        (if-let [value (field->clj config message field-descriptor)]
          (assoc! b (field-name config field-descriptor) value)
          b))
      (transient {})
      (.getFields message-type))))

(defn RpcResponse->clj
  "Convert a RpcResponse containing the response from a gRPC server to a response map
  that is the protobuf response message with metadata about the gRPC server response.

  Example:
  ^{:status {:code \"OK\" :description nil}
    :headers {}
    :trailers {}}
  {:field1 \"value1\"
   :field2 123}
  "
  [config
   ^RpcResponse response
   ^Descriptors$Descriptor message-type]
  (let [raw-message (.getMessage response)
        message (Message->clj config raw-message message-type)
        raw-headers (.getHeaders response)
        raw-trailers (.getTrailers response)
        raw-status (.getStatus response)]
    (with-meta
      message
      (merge (Status->clj raw-status)
             {:headers (MessageConverter/metadataToClj raw-headers)
              :trailers (MessageConverter/metadataToClj raw-trailers)}
             (when (:include-raw-types config)
               {:raw-message raw-message
                :raw-headers raw-headers
                :raw-trailers raw-trailers
                :raw-status raw-status})))))

(defn get-field-value
  "Return the value of a field if it exists on the map, else returns nil.
  A field that's set to a nil value is the same as not existing, since protobuf
  does not have null values."
  [message-map ^Descriptors$FieldDescriptor field-desc]
  (cond
    (contains? message-map (.getName field-desc))
    (get message-map (.getName field-desc))

    (contains? message-map (keyword (.getName field-desc)))
    (get message-map (keyword (.getName field-desc)))

    (contains? message-map (.getIndex field-desc))
    (get message-map (.getIndex field-desc))))

(defn clj->field-value
  "Convert a clojure protobuf value to a type appropriate for the protobuf java implementation."
  [field-value
   ^Descriptors$FieldDescriptor f-desc]
  (when (some? field-value)
    (let [java-type
          (.name (.getJavaType f-desc))
          value
          (case java-type
            "INT"
            (cond
              (instance? Long field-value) (Math/toIntExact field-value)
              (instance? Integer field-value) field-value)

            "LONG"
            (cond
              (instance? Long field-value) field-value
              (instance? Integer field-value) (.longValue ^Integer field-value))

            "FLOAT"
            (cond
              (instance? Number field-value) (.floatValue ^Number field-value))

            "DOUBLE"
            (cond
              (instance? Number field-value) (.doubleValue ^Number field-value))

            "STRING"
            (cond
              (instance? String field-value) field-value)

            "BOOLEAN"
            (cond
              (instance? Boolean field-value) field-value)

            ("BYTE_STRING")
            (cond
              (bytes? field-value) field-value
              (instance? ByteString field-value) field-value)

            "ENUM"
            (let [^Descriptors$EnumDescriptor enum-type (.getEnumType f-desc)]
              (cond
                (instance? Integer field-value) (.findValueByNumber enum-type ^int field-value)
                (instance? Long field-value) (.findValueByNumber enum-type ^int (.intValue ^Long field-value))
                (keyword? field-value) (.findValueByName enum-type (name field-value))
                (string? field-value) (.findValueByName enum-type field-value)
                (instance? Internal$EnumLite field-value) field-value))

            "MESSAGE"
            (cond
              (instance? Map field-value)
              (clj->Message field-value
                            (.getMessageType f-desc))
              (instance? MessageLite field-value) field-value)

            (throw (RuntimeException.
                     (format "Unsupported field type [%s]" java-type))))]
      (when-not (some? value)
        (throw (RuntimeException.
                 (format "%s %s Cannot coerce field value of type [%s] to field type [%s]"
                         (instance? Boolean field-value)
                         field-value
                         (type field-value)
                         java-type))))
      value)))


(defn clj->field
  [message-map ^Descriptors$FieldDescriptor f-desc]
  (cond
    (.isRepeated f-desc)
    (mapv #(clj->field-value % f-desc) (get-field-value message-map f-desc))
    (.isOptional f-desc)
    (when-let [field-value (get-field-value message-map f-desc)]
      (clj->field-value field-value f-desc))
    :else
    (if-let [field-value (get-field-value message-map f-desc)]
      (clj->field-value field-value f-desc)
      (throw (RuntimeException.
               (format "Field [%s] is required but no value was supplied" f-desc))))))

(defn clj->Message
  "Convert a clojure map of fields and values to a protobuf message"
  ^DynamicMessage
  [message-map ^Descriptors$Descriptor message-type]
  (let [^DynamicMessage$Builder message-builder (DynamicMessage/newBuilder message-type)]
    (doseq [^Descriptors$FieldDescriptor f-desc (.getFields message-type)
            :let [field-value (clj->field message-map f-desc)]
            :when (some? field-value)]
      (.setField message-builder f-desc ^Object field-value))
    (.build message-builder)))

(defn StatusRuntimeException->exception-info
  [config ^StatusRuntimeException err]
  (let [raw-status (.getStatus err)
        raw-trailers (.getTrailers err)
        status (Status->clj raw-status)
        code (.getCode raw-status)
        description (.getDescription raw-status)
        error-msg (format "gRPC server responded with error status=[%s] description=[%s]"
                          code description)
        error-map (merge
                    status
                    {:trailers (MessageConverter/metadataToClj raw-trailers)}
                    (when (:include-raw-types config)
                      {:raw-status raw-status
                       :raw-trailers raw-trailers}))]
    (ex-info error-msg error-map)))
