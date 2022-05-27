package naply.grpc_banter;

import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;

final class HeaderReflectingServerCall<ReqT, RespT>
        extends ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT> {

    private final Metadata requestHeaders;

    public HeaderReflectingServerCall(ServerCall<ReqT, RespT> delegate, Metadata requestHeaders) {
        super(delegate);
        this.requestHeaders = requestHeaders;
    }

    @Override
    public void sendHeaders(Metadata headers) {
        headers.merge(requestHeaders);
        super.sendHeaders(headers);

    }
}
