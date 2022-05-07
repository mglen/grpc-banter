(ns naply.grpc-banter.schema
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.util :as mu]
            [malli.error :as me])
  (:import (com.google.protobuf Descriptors$Descriptor
                                Descriptors$FieldDescriptor ByteString)))

(def ConverterConfigSchema
  [:map {:closed true}
   [:enums-as-keywords [:boolean {:default true}]]
   [:response-fields-as-keywords [:boolean {:default true}]]
   [:include-raw-types [:boolean {:default false}]]
   [:optional-fields-required [:boolean {:default false}]]])

(def ClientConfigSchema
  (mu/merge
    ConverterConfigSchema
    [:map {:closed true}
     [:target :string]
     [:file-descriptor-set :string]]))

(def RequestSchema
  (mu/merge
    ConverterConfigSchema
    [:multi {:dispatch #(contains? % :service)}
     [false [:map {:closed true}
             [:method [:re {:error/message "Must be in form 'package.Service/Method'"} #"[^/]+/[^/]+"]]]]
     [true [:map {:closed true}
            [:method :string]
            [:service :string]]]]))

(defn- decode-config
  "Return the configuration with defaults applied. Throw if the config does not conform."
  [schema config]
  (let [config (m/decode schema config mt/default-value-transformer)
        errors (me/humanize (m/explain schema config))]
    (when errors
      (throw (IllegalArgumentException. (str "Errors in configuration " errors))))
    config))

(def decode-client-config (partial decode-config ClientConfigSchema))
(defn decode-request [request client-config]
  ;; Ignoring output with defaults, we only want to validate
  (decode-config RequestSchema request)
  ;; Apply client config, with request config overriding values
  (merge (m/decode ConverterConfigSchema client-config mt/strip-extra-keys-transformer)
         request))


(declare message-schema)

(def custom-errors
  ;; TODO, error message with protobuf schema info
  (-> me/default-errors
      (assoc ::m/missing-key
             {:error/fn (fn [_err _] (str "field is required"))})
      ;; TODO: print type of parent value, check for toString. Walk stack to check how its rendering schema
      #_(assoc ::m/invalid-type
               {:error/fn (fn [{:keys [value schema] :as wah} _]
                            (str "value " value " has wrong type for schema " schema))})))

(defn field-type-schema
  "Provides the malli schema for the field type of a given protobuf field"
  [config ^Descriptors$FieldDescriptor f-desc]
  (case (.name (.getJavaType f-desc))
        "INT" [:int {:min Integer/MIN_VALUE :max Integer/MAX_VALUE}]
        "LONG" [:int {:min Long/MIN_VALUE :max Long/MAX_VALUE}]
        "FLOAT" float?
        "DOUBLE" [:or float? :double]
        "BOOLEAN" :boolean
        "STRING" :string
        "BYTE_STRING" [:fn
                       {:error/message "Should be a ByteString"}
                       (partial instance? ByteString)]
        "ENUM"  (let [enum-values (.getValues (.getEnumType f-desc))]
                  [:or (into [:enum] (map #(.getName %) enum-values)) ;; value as string
                       (into [:enum] (map #(.getNumber %) enum-values)) ;; value s as field number
                       (into [:enum] (map #(keyword (.getName %)) enum-values))]) ;; value as keyword
        "MESSAGE" (message-schema config (.getMessageType f-desc))))

(defn field-schema
  "Provides the malli schema for a protobuf message field"
  [config ^Descriptors$FieldDescriptor f-desc]
  (cond
    (.isRepeated f-desc)
    [(.getName f-desc) [:sequential (field-type-schema config f-desc)]]

    (and (.isOptional f-desc)
         (not (:optional-fields-required config)))
    [(.getName f-desc) {:optional true} (field-type-schema config f-desc)]

    :else
    [(.getName f-desc) (field-type-schema config f-desc)]))


(defn message-schema [config ^Descriptors$Descriptor descriptor]
  (into [:map {:closed true}]
        (map #(field-schema config %)
             (.getFields descriptor))))

(defn validate [^Descriptors$Descriptor descriptor config proto]
  (let [Schema (message-schema config descriptor)]
    (me/humanize
      (m/explain Schema
                 (m/decode Schema proto
                           ;; Turn all :keyword keys to strings
                           (mt/key-transformer {:decode name})))
      {:errors custom-errors})))

(comment
  (do
    (import '(io.naply.dynamic_grpc FileDescriptorRegistry))
    (def fdr (FileDescriptorRegistry/fromFileDescriptorSetFile "target/file_descriptor_set.dsc"))
    (def msg (.findMessageTypeByFullName fdr "io.naply.runtime_grpc.ComplexMessage"))
    (def nested-msg (.findMessageTypeByFullName fdr "io.naply.runtime_grpc.NestedMessage"))
    (require '[malli.core :as m])
    (require '[malli.generator :as mg])
    (mg/generate (message-schema {} msg))
    (mg/generate (message-schema {} nested-msg))))