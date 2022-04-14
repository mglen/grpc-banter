(ns naply.grpc-banter.schema
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [malli.error :as me])
  (:import (com.google.protobuf Descriptors$Descriptor
                                Descriptors$FieldDescriptor)))

(def ConfigSchema
  [:map
   [:target :string]
   [:file-descriptor-set :string]
   [:enums-as-keywords {:optional true :default true} :boolean]
   [:response-fields-as-keywords {:optional true :default true} :boolean]
   [:include-raw-types {:optional true :default false} :boolean]
   [:optional-fields-required {:optional true :default false} :boolean]])

(defn valid-config? [config]
  (when-let [err (me/humanize (m/explain ConfigSchema config))]
    (throw (IllegalArgumentException. (str "Errors in configuration " err)))))

(declare gen-schema)

(def custom-errors
  (-> me/default-errors
      (assoc ::m/missing-key
             {:error/fn (fn [_err _] (str "field is required"))})
      #_(assoc ::m/invalid-type
               {:error/fn (fn [{:keys [value schema] :as wah} _]
                            (str "value " value " has wrong type for schema " schema))})))

(defn field-type [config ^Descriptors$FieldDescriptor f-desc]
  (case (.name (.getJavaType f-desc))
        "INT" [:int {:min Integer/MIN_VALUE :max Integer/MAX_VALUE}]
        "LONG" [:int {:min Long/MIN_VALUE :max Long/MAX_VALUE}]
        "FLOAT" [:double {:min Float/MIN_VALUE :max Float/MAX_VALUE}]
        "DOUBLE" [:double {:min Double/MIN_VALUE :max Double/MAX_VALUE}]
        "BOOLEAN" :boolean
        "STRING" :string
        "BYTE_STRING" bytes?
        "ENUM"  (let [enum-values (.getValues (.getEnumType f-desc))]
                  [:or (into [:enum] (map #(.getName %) enum-values)) ;; value as string
                       (into [:enum] (map #(.getNumber %) enum-values)) ;; value s as field number
                       (into [:enum] (map #(keyword (.getName %)) enum-values))]) ;; value as keyword
        "MESSAGE" (gen-schema config (.getMessageType f-desc))))

(defn message-field [config ^Descriptors$FieldDescriptor f-desc]
  (cond
    (.isRepeated f-desc)
    [(.getName f-desc) [:sequential (field-type config f-desc)]]

    (and (.isOptional f-desc)
         (not (:optional-fields-required config)))
    [(.getName f-desc) {:optional true} (field-type config f-desc)]

    :else
    [(.getName f-desc) (field-type config f-desc)]))


(defn gen-schema [config ^Descriptors$Descriptor descriptor]
  (into [:map]
        (map #(message-field config %)
             (.getFields descriptor))))

(defn validate [^Descriptors$Descriptor descriptor config proto]
  (let [Schema (gen-schema config descriptor)]
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
    (mg/generate (gen-schema msg))
    (mg/generate (gen-schema nested-msg))))