package naply.grpc_banter;

import clojure.lang.*;
import com.google.protobuf.*;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import naply.grpc_banter.internal.RpcResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Function;

public class MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(MessageConverter.class);

    public static class Config {
        final boolean enumAsKeyword;
        final boolean responseFieldsAsKeywords;
        final boolean includeRawTypes;
        /**
         * Whether to require all fields, including proto2 optionals. If false proto3 fields are treated as optional
         * and set to their default value when unset.
         */
        final boolean optionalFieldsRequired;

        public Config(Map<Keyword, Object> config) {
            this.enumAsKeyword = (Boolean) config.getOrDefault(Keyword.intern("enums-as-keywords"), true);
            this.responseFieldsAsKeywords = (Boolean) config.getOrDefault(Keyword.intern("response-fields-as-keywords"), true);
            this.includeRawTypes = (Boolean) config.getOrDefault(Keyword.intern("include-raw-types"), false);
            this.optionalFieldsRequired = (Boolean) config.getOrDefault(Keyword.intern("optional-fields-required"), false);
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<Object> handleField(Message message,
                                                Descriptors.FieldDescriptor field,
                                                Function<Object, Object> effect) {
        if (field.isOptional()) {
            if (message.hasField(field)) {
                return Optional.of(effect.apply(message.getField(field)));
            } else {
                return Optional.empty();
            }
        } else if (field.isRequired()) {
            return Optional.of(effect.apply(message.getField(field)));
        } else if (field.isRepeated()) {
            ITransientCollection transientVector = PersistentVector.create().asTransient();

            for (Object o : (List<Object>) message.getField(field)) {
                transientVector.conj(effect.apply(o));
            }
            return Optional.of(transientVector.persistent());
        } else {
            throw new RuntimeException(String.format(
                    "Found field=[%s] label=[%s] that is neither optional,required,repeated",
                    field.getFullName(), field.toProto().getLabel()));
        }
    }

    public static PersistentHashMap responseToClj(Descriptors.Descriptor messageType, RpcResponse response, Config config) {
        PersistentHashMap map = messageToClj(messageType, response.getMessage(), config);
        ATransientMap transientMap = PersistentHashMap.EMPTY.asTransient();

        transientMap.assoc(Keyword.intern("status"), statusToClj(response.getStatus()));
        transientMap.assoc(Keyword.intern("headers"), asciiMetadataToClj(response.getHeaders()));
        transientMap.assoc(Keyword.intern("trailers"), asciiMetadataToClj(response.getTrailers()));

        if (config.includeRawTypes) {
            transientMap.assoc(Keyword.intern("raw-status"), response.getStatus());
            transientMap.assoc(Keyword.intern("raw-headers"), response.getHeaders());
            transientMap.assoc(Keyword.intern("raw-trailers"), response.getTrailers());
            transientMap.assoc(Keyword.intern("raw-message"), response.getMessage());
        }
        return map.withMeta(transientMap.persistent());
    }

    private static IPersistentMap statusToClj(Status status) {
        return PersistentHashMap.create(
                Keyword.intern("code"), status.getCode().name(),
                Keyword.intern("description"), status.getDescription()
        );
    }

    public static ExceptionInfo statusRuntimeExceptionToExceptionInfo(StatusRuntimeException e, Config config) {
        ATransientMap transientMap = PersistentHashMap.EMPTY.asTransient();

        transientMap.assoc(Keyword.intern("status"), statusToClj(e.getStatus()));
        transientMap.assoc(Keyword.intern("trailers"), asciiMetadataToClj(e.getTrailers()));

        if (config.includeRawTypes) {
            transientMap.assoc(Keyword.intern("raw-status"), e.getStatus());
            transientMap.assoc(Keyword.intern("raw-trailers"), e.getTrailers());
        }

        String errMsg = String.format(
                "gRPC server responded with error status=[%s] description=[%s]",
                e.getStatus().getCode(),
                e.getStatus().getDescription());
        return new ExceptionInfo(errMsg, transientMap.persistent());
    }

    private static IPersistentMap asciiMetadataToClj(@Nullable Metadata metadata) {
        if (metadata == null) return PersistentHashMap.EMPTY;
        ATransientMap transientMap = PersistentHashMap.EMPTY.asTransient();
        for (String key : metadata.keys()) {
            Iterable<String> vals = metadata.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            if (vals != null) {
                transientMap.assoc(key, PersistentVector.create(vals));
            } else {
                transientMap.assoc(key, PersistentVector.EMPTY);
            }
        }
        return transientMap.persistent();
    }

    public static PersistentHashMap messageToClj(Descriptors.Descriptor messageType, Message message, Config config) {
        ATransientMap transientMap = PersistentHashMap.EMPTY.asTransient();
        for (Descriptors.FieldDescriptor field : messageType.getFields()) {
            Optional<Object> handledField;
            switch (field.getJavaType()) {
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case STRING:
                case BOOLEAN:
                case BYTE_STRING: // TODO: clojure binary type?
                    handledField = handleField(message, field, v -> v);
                    break;
                case ENUM:
                    handledField = handleField(message, field, v -> {
                        Descriptors.EnumValueDescriptor e = (Descriptors.EnumValueDescriptor) v;
                        if (config.enumAsKeyword) {
                            return Keyword.intern(e.getName());
                        } else {
                            return e.getNumber();
                        }
                    });
                    break;
                case MESSAGE:
                    handledField = handleField(message, field,
                            v -> messageToClj(field.getMessageType(), (Message) v, config));
                    break;
                default:
                    throw new RuntimeException("Unsupported field type " + field.getJavaType());
            }
            handledField.ifPresent(v -> {
                Object fieldName = config.responseFieldsAsKeywords
                        ? Keyword.intern(field.getName())
                        : field.getName();
                transientMap.assoc(fieldName, v);
            });
        }
        return (PersistentHashMap) transientMap.persistent();
    }

    private static Optional<Object> handleWriteField(Associative map,
                                                     Descriptors.FieldDescriptor field,
                                                     Config config,
                                                     Function<Object, Object> effect) {
        String name = field.getName();
        Object fieldVal;
        if (map.containsKey(name)) {
            fieldVal = map.entryAt(name).val();
        } else if (map.containsKey(Keyword.intern(name))) {
            fieldVal = map.entryAt(Keyword.intern(name)).val();
        } else {
            // No entry in map for field
            if (field.getFile().getSyntax() == Descriptors.FileDescriptor.Syntax.PROTO2
                    && field.isRequired()) {
                throw new RuntimeException(String.format(
                        "Required proto2 field=[%s] missing from=%s",
                        field.getFullName(), map));
            } else if (config.optionalFieldsRequired) {
                throw new RuntimeException(String.format(
                        "requireAllFields=[true] and field=[%s] missing from=%s",
                        field.getFullName(), map));
            } else {
                return Optional.empty();
            }
        }

        // TODO: use .hasOptionalKeyword()
        if (field.isRepeated()) {
            if (fieldVal instanceof Collection) {
                // the Message *must* receive a List<>
                List<Object> list = new ArrayList<>();
                for (Object o : (Collection<?>) fieldVal) {
                    list.add(effect.apply(o));
                }
                return Optional.of(list);
            } else if (fieldVal instanceof clojure.lang.ISeq) {
                List<Object> list = new ArrayList<>();
                // TODO, consider a more efficient solution
                for (Object o : PersistentVector.create((ISeq) fieldVal)) {
                    list.add(effect.apply(o));
                }
                return Optional.of(list);
            } else {
                throw new RuntimeException(String.format(
                        "Can only convert List and ISeq types to repeatable fields, got=[%s]",
                        fieldVal.getClass()));
            }
        } else {
            return Optional.of(effect.apply(fieldVal));
        }
    }

    /**
     * Convert a clojure map into a protobuf {@link DynamicMessage}. Assumes the clojure map has already
     * bee validated to contain fields with correct values.
     */
    public static DynamicMessage cljToMessage(Descriptors.Descriptor messageType, Associative map, Config config) {
        DynamicMessage.Builder message = DynamicMessage.newBuilder(messageType);
        for (Descriptors.FieldDescriptor field : messageType.getFields()) {
            Optional<Object> writeFieldResult;
            switch (field.getJavaType()) {
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case STRING:
                case BOOLEAN:
                case BYTE_STRING: // TODO: assumes getting a ByteArray, what are reasonable clojure types?
                    writeFieldResult = handleWriteField(map, field, config, v -> v);
                    break;
                case ENUM:
                    writeFieldResult = handleWriteField(map, field, config, v -> {
                        log.debug("Handling enum {} value {}", field, v);
                        Descriptors.EnumDescriptor enumType = field.getEnumType();
                        if (v instanceof Keyword) {
                            return enumType.findValueByName(((Keyword) v).getName());
                        } else if (v instanceof String) {
                            return enumType.findValueByName((String) v);
                        } else if (v instanceof Number) {
                            return enumType.findValueByNumber(((Number) v).intValue());
                        } else {
                            throw new RuntimeException(String.format(
                                    "Cannot handle field=[%s] value=[%s] (type=[%s]) as enum",
                                    field.getName(),
                                    v,
                                    v.getClass()));
                        }
                    });
                    break;
                case MESSAGE:
                    writeFieldResult = handleWriteField(map, field, config, v -> {
                        if (v instanceof Associative) {
                            return cljToMessage(field.getMessageType(), (Associative) v, config);
                        } else {
                            throw new RuntimeException(String.format(
                                    "Cannot handle field=[%s] value=[%s] (type=[%s]) as message",
                                    field.getName(),
                                    v,
                                    v.getClass()));
                        }
                    });
                    break;
                default:
                    throw new RuntimeException("Unsupported field type " + field.getJavaType());
            }
            writeFieldResult.ifPresent(v -> message.setField(field, v));
        }
        return message.build();
    }
}
