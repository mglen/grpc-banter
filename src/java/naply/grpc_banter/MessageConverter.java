package naply.grpc_banter;

import clojure.lang.*;
import io.grpc.Metadata;
import javax.annotation.Nullable;
import java.util.Map;

public class MessageConverter {

    public static IPersistentMap asciiMetadataToClj(@Nullable Metadata metadata) {
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

    public static Metadata cljToAsciiMetadata(@Nullable Map<Object, Object> metadata) {
        Metadata headers = new Metadata();
        if (metadata == null) return headers;
        for (Map.Entry<Object, Object> item : metadata.entrySet()) {
            Metadata.Key<String> key = getMetadataKey(item.getKey());
            Object cljVal = item.getValue();
            if (cljVal instanceof Iterable) {
                for (Object v: ((Iterable<?>) cljVal)) {
                    if (v instanceof String) {
                        headers.put(key, (String) v);
                    } else {
                        throw new RuntimeException(String.format(
                                "Metadata value in list must be a string, but was=[%s]", v));
                    }
                }
            } else if (cljVal instanceof String) {
                headers.put(key, (String) cljVal);
            } else {
                throw new RuntimeException(String.format(
                        "Metadata value must be a string or list, but was=[%s]", cljVal));
            }
        }
        return headers;
    }

    private static Metadata.Key<String> getMetadataKey(Object cljKey) {
        String key;
        if (cljKey instanceof Keyword) {
            key = ((Keyword) cljKey).getName();
        } else if (cljKey instanceof String) {
            key = (String) cljKey;
        } else {
            throw new RuntimeException(String.format(
                    "Header key must be a String or Keyword but was class=[%s] value=[%s]",
                    cljKey.getClass(),
                    cljKey));
        }
        return Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
    }
}
