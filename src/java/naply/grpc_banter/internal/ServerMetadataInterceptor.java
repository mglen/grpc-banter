package naply.grpc_banter.internal;

import io.grpc.*;

public final class ServerMetadataInterceptor implements ClientInterceptor {

    private final RpcResponse.Builder rpcResponseBuilder;

    private ServerMetadataInterceptor(RpcResponse.Builder rpcResponseBuilder) {
        this.rpcResponseBuilder = rpcResponseBuilder;
    }

    public static ServerMetadataInterceptor create(RpcResponse.Builder rpcResponseBuilder) {
        return new ServerMetadataInterceptor(rpcResponseBuilder);
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new MetadataCapturingClientCall<>(rpcResponseBuilder, next.newCall(method, callOptions));
    }

    private static final class MetadataCapturingClientCall<ReqT, RespT>
            extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

        private final RpcResponse.Builder rpcResponseBuilder;

        private MetadataCapturingClientCall(RpcResponse.Builder rpcResponseBuilder, ClientCall<ReqT, RespT> delegate) {
            super(delegate);
            this.rpcResponseBuilder = rpcResponseBuilder;
        }

        @Override
        public void start(Listener<RespT> responseListener, Metadata headers) {
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
