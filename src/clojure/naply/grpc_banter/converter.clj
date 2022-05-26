(ns naply.grpc-banter.converter
  (:import (com.google.protobuf Message Descriptors$FieldDescriptor
                                Descriptors$Descriptor
                                DynamicMessage
                                ByteString
                                Internal$EnumLite
                                MessageLite
                                Descriptors$EnumValueDescriptor)
           (java.util Map)
           (io.grpc StatusRuntimeException Status)
           (naply.grpc_banter MessageConverter)
           (naply.grpc_banter.internal RpcResponse)))

(declare message->clj)
(declare clj->message)

(defn Status->clj
  [^Status status]
  {:status {:code (-> status .getCode .name)
            :description (.getDescription status)}})

(defn field-mapper [config ^Descriptors$FieldDescriptor field-desc]
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
    (fn [^Message msg] (message->clj config msg (.getDescriptorForType msg)))

    (throw (RuntimeException. (str "Unsupported field type" (.getJavaType field-desc))))))

(defn field-name [config f-desc]
  (if (:response-fields-as-keywords config)
    (keyword (.getName f-desc))
    (.getName f-desc)))

(defn field->clj
  "Return the field value for the field on a message.
  Returns nil if the field is optional and not defined."
  [config
   ^Message message
   ^Descriptors$FieldDescriptor f-desc]
  (let [f (field-mapper config f-desc)]
    (cond
      (.isOptional f-desc) (when (.hasField message f-desc)
                             (f (.getField message f-desc)))
      (.isRequired f-desc) (f (.getField message f-desc))
      (.isRepeated f-desc) (mapv f (.getField message f-desc))
      :else (throw (RuntimeException.
                     (format "Found field=[%s] label=[%s] that is neither optional,required,repeated"
                             (.getFullName f-desc)
                             (-> f-desc .toProto .getLabel)))))))


(defn message->clj
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
        message (message->clj config raw-message message-type)
        raw-headers (.getHeaders response)
        raw-trailers (.getTrailers response)
        raw-status (.getStatus response)]
    (with-meta
      message
      (merge (Status->clj raw-status)
             {:headers (MessageConverter/asciiMetadataToClj raw-headers)
              :trailers (MessageConverter/asciiMetadataToClj raw-trailers)}
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
  [config
   field-value
   ^Descriptors$FieldDescriptor f-desc]
  (when (some? field-value)
    (let [java-type
          (.name (.getJavaType f-desc))
          value
          (case java-type
            "INT"
            (cond
              (instance? Long field-value) (.intValue field-value)
              (instance? Integer field-value) field-value)

            "LONG"
            (cond
              (instance? Long field-value) field-value
              (instance? Integer field-value) (.longValue field-value))

            "FLOAT"
            (cond
              (instance? Number field-value) (.floatValue field-value))

            "DOUBLE"
            (cond
              (instance? Number field-value) (.doubleValue field-value))

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
            (let [enum-type (.getEnumType f-desc)]
              (cond
                (instance? Integer field-value) (.findValueByNumber enum-type field-value)
                (instance? Long field-value) (.findValueByNumber enum-type (.intValue field-value))
                (keyword? field-value) (.findValueByName enum-type (name field-value))
                (string? field-value) (.findValueByName enum-type field-value)
                (instance? Internal$EnumLite field-value) field-value))

            "MESSAGE"
            (cond
              (instance? Map field-value)
              (clj->message config
                            field-value
                            (.getMessageType f-desc))
              (instance? MessageLite field-value) field-value)
            ;; Catchall
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


(defn clj->field [config
                  message-map
                  ^Descriptors$FieldDescriptor f-desc]
  (cond
    (.isRepeated f-desc)
    (mapv #(clj->field-value config % f-desc) (get-field-value message-map f-desc))
    (.isOptional f-desc)
    (when-let [field-value (get-field-value message-map f-desc)]
      (clj->field-value config field-value f-desc))
    :else
    (if-let [field-value (get-field-value message-map f-desc)]
      (clj->field-value config field-value f-desc)
      (throw (RuntimeException.
               (format "Field [%s] is required but no value was supplied" f-desc))))))

(defn clj->message
  "Convert a clojure map of fields and values to a protobuf message"
  ^DynamicMessage
  [config message-map ^Descriptors$Descriptor message-type]
  (let [message-builder (DynamicMessage/newBuilder message-type)]
    (doseq [f-desc (.getFields message-type)
            :let [field-value (clj->field config message-map f-desc)]
            :when (some? field-value)]
      (.setField message-builder f-desc field-value))
    (.build message-builder)))

(defn statusRuntimeException->exception-info
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
                    {:trailers (MessageConverter/asciiMetadataToClj raw-trailers)}
                    (when (:include-raw-types config)
                      {:raw-status raw-status
                       :raw-trailers raw-trailers}))]
    (ex-info error-msg error-map)))
