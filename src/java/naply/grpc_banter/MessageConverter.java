package naply.grpc_banter;

import clojure.lang.*;
import com.google.protobuf.DescriptorProtos;
import io.grpc.Metadata;
import javax.annotation.Nullable;
import java.util.Map;

public class MessageConverter {

    public static IPersistentMap metadataToClj(@Nullable Metadata metadata) {
        if (metadata == null) return PersistentHashMap.EMPTY;
        ITransientMap transientMap = PersistentHashMap.EMPTY.asTransient();
        for (String key : metadata.keys()) {
            Iterable<?> vals;
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                vals = metadata.getAll(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER));
            } else {
                vals = metadata.getAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            }
            if (vals != null) {
                transientMap = transientMap.assoc(key, PersistentVector.create(vals));
            }
        }
        return transientMap.persistent();
    }

    public static Metadata cljToMetadata(@Nullable Map<Object, Object> metadata) {
        Metadata headers = new Metadata();
        if (metadata == null) return headers;
        for (Map.Entry<Object, Object> item : metadata.entrySet()) {
            String key = getMetadataKey(item.getKey());
            boolean isBinary = key.endsWith(Metadata.BINARY_HEADER_SUFFIX);
            Object cljVal = item.getValue();
            if (cljVal instanceof Iterable) {
                for (Object v : ((Iterable<?>) cljVal)) {
                    if (!isBinary && v instanceof String) {
                        headers.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), (String) v);
                    } else if (isBinary && v instanceof byte[]) {
                        headers.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), (byte[]) v);
                    } else {
                        throw new RuntimeException(String.format(
                                "Metadata for key=[%s] in list must be a %s, but was=[%s]",
                                key, isBinary ? "byte[]": "string", v));
                    }
                }
            } else if (!isBinary && cljVal instanceof String) {
                headers.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), (String) cljVal);
            } else if (isBinary && cljVal instanceof byte[]) {
                headers.put(Metadata.Key.of(key, Metadata.BINARY_BYTE_MARSHALLER), (byte[]) cljVal);
            } else {
                String type = isBinary ? "byte[]" : "string";
                throw new RuntimeException(String.format(
                        "Metadata for key=[%s] must be a %s or list of %s, but was=[%s]",
                        key, type, type, cljVal));
            }
        }
        return headers;
    }

    private static String getMetadataKey(Object cljKey) {
        if (cljKey instanceof Keyword) {
            return ((Keyword) cljKey).getName();
        } else if (cljKey instanceof String) {
            return (String) cljKey;
        } else {
            throw new RuntimeException(String.format(
                    "Header key must be a String or Keyword but was class=[%s] value=[%s]",
                    cljKey.getClass(),
                    cljKey));
        }
    }
}
