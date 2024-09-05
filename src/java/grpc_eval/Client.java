package grpc_eval;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import grpc_eval.internal.RpcResponse;
import grpc_eval.internal.ServerMetadataInterceptor;
import io.grpc.*;
import io.grpc.stub.ClientCalls;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class Client implements Closeable {

    private final ManagedChannel channel;

    public static Client create(String target, @Nullable Boolean useTls) {
        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forTarget(target)
                .directExecutor()
                .disableRetry();

        channelBuilder = Boolean.TRUE.equals(useTls)
                ? channelBuilder.useTransportSecurity()
                : channelBuilder.usePlaintext();

        return new Client(channelBuilder.build());
    }

    public Client(ManagedChannel managedChannel) {
        this.channel = managedChannel;
    }

    public RpcResponse callMethod(
            Descriptors.MethodDescriptor methodDescriptor,
            DynamicMessage message,
            Metadata headers,
            long deadlineMilliseconds)
            throws StatusRuntimeException {
        MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor = grpcMethodDescriptor(methodDescriptor);
        if (MethodDescriptor.MethodType.UNARY.equals(grpcMethodDescriptor.getType())) {
            RpcResponse.Builder responseBuilder = RpcResponse.builder();
            Channel metadataCollectingChannel = ClientInterceptors.intercept(
                    channel,
                    new ServerMetadataInterceptor(responseBuilder, headers));
            CallOptions callOptions = CallOptions.DEFAULT
                    .withDeadlineAfter(deadlineMilliseconds, TimeUnit.MILLISECONDS);
            DynamicMessage responseMessage = ClientCalls.blockingUnaryCall(
                    metadataCollectingChannel,
                    grpcMethodDescriptor,
                    callOptions,
                    message);
            return responseBuilder.message(responseMessage).build();
        } else {
            throw new RuntimeException(String.format("Method type=[%s] is not supported", grpcMethodDescriptor.getType()));
        }
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor(Descriptors.MethodDescriptor methodDescriptor) {
        if (methodDescriptor.isClientStreaming() || methodDescriptor.isServerStreaming()) {
            throw new IllegalArgumentException("Calls for streaming methods not supported");
        }
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(methodDescriptor.getService().getFullName() + "/" + methodDescriptor.getName())
                .setRequestMarshaller(buildDynamicMarshaller(methodDescriptor.getInputType()))
                .setResponseMarshaller(buildDynamicMarshaller(methodDescriptor.getOutputType()))
                .build();
    }

    private static MethodDescriptor.Marshaller<DynamicMessage> buildDynamicMarshaller(Descriptors.Descriptor type) {
        Parser<DynamicMessage> parser = DynamicMessage.newBuilder(type).buildPartial().getParserForType();
        return new MethodDescriptor.Marshaller<DynamicMessage>() {
            @Override
            public InputStream stream(DynamicMessage dynamicMessage) {
                return dynamicMessage.toByteString().newInput();
            }

            @Override
            public DynamicMessage parse(InputStream inputStream) {
                try {
                    return parser.parseFrom(inputStream);
                } catch (InvalidProtocolBufferException e) {
                    throw new RuntimeException(String.format("Could not parse server response as type=[%s]", type.getFullName()), e);
                }

            }
        };
    }

    @Override
    public void close() {
        this.channel.shutdownNow();
    }
}
