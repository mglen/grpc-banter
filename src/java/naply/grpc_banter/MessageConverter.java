package naply.grpc_banter;

import clojure.lang.*;
import io.grpc.Metadata;
import javax.annotation.Nullable;

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
}
