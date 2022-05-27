package naply.grpc_banter.internal;

import io.grpc.*;

public final class ServerMetadataInterceptor implements ClientInterceptor {

    private final RpcResponse.Builder rpcResponseBuilder;
    private final Metadata requestHeaders;

    public ServerMetadataInterceptor(RpcResponse.Builder rpcResponseBuilder, Metadata requestHeaders) {
        this.rpcResponseBuilder = rpcResponseBuilder;
        this.requestHeaders = requestHeaders;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new MetadataCapturingClientCall<>(next.newCall(method, callOptions));
    }

    private final class MetadataCapturingClientCall<ReqT, RespT>
            extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

        private MetadataCapturingClientCall(ClientCall<ReqT, RespT> delegate) {
            super(delegate);
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
            headers.merge(requestHeaders);
            super.start(new MetadataCapturingClientCallListener(responseListener), headers);
        }

        private final class MetadataCapturingClientCallListener
                extends ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT> {
            private MetadataCapturingClientCallListener(ClientCall.Listener<RespT> delegate) {
                super(delegate);
            }

            @Override
            public void onHeaders(Metadata headers) {
                super.onHeaders(headers);
                rpcResponseBuilder.headers(headers);
            }

            @Override
            public void onMessage(RespT message) {
                super.onMessage(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                super.onClose(status, trailers);
                rpcResponseBuilder.status(status);
                rpcResponseBuilder.trailers(trailers);
            }
        }
    }
}
