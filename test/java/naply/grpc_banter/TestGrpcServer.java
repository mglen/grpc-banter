package naply.grpc_banter;

import io.grpc.*;
import io.grpc.stub.StreamObserver;

import java.io.Closeable;
import java.io.IOException;

public class TestGrpcServer implements Closeable {

    private final Server server;

    public static TestGrpcServer create(int port) throws IOException {
        return new TestGrpcServer(port);
    }

    private TestGrpcServer(int port) throws IOException {
        this.server = ServerBuilder.forPort(port)
                .addService(new EchoService())
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        // TODO: inject headers/trailers
                        return next.startCall(call, headers);
                    }
                })
                .build();
        this.server.start();
    }

    @Override
    public void close() {
        server.shutdownNow();
    }

    public int getPort() {
        return server.getPort();
    }

    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }

    private static class EchoService extends EchoServiceGrpc.EchoServiceImplBase {
        @Override
        public void echo(EchoServiceProtos.EchoRequest request, StreamObserver<EchoServiceProtos.EchoResponse> responseObserver) {
            EchoServiceProtos.EchoResponse response = EchoServiceProtos.EchoResponse.newBuilder()
                    .setReply(request.getMessage())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void error(EchoServiceProtos.ErrorRequest request, StreamObserver<EchoServiceProtos.ErrorResponse> responseObserver) {
            responseObserver.onError(Status.INTERNAL.withDescription("All requests will fail.").asException());
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        try (TestGrpcServer testGrpcServer = TestGrpcServer.create(8005)) {
            testGrpcServer.awaitTermination();
        }
    }
}
