package naply.grpc_banter;

import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Parser;
import io.grpc.*;
import io.grpc.stub.ClientCalls;
import naply.grpc_banter.internal.RpcResponse;
import naply.grpc_banter.internal.ServerMetadataInterceptor;

import java.io.Closeable;
import java.io.InputStream;

public class Client implements Closeable {

    private final ManagedChannel channel;

    public static Client create(String target) {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
        return new Client(channel);
    }

    public Client(ManagedChannel managedChannel) {
        this.channel = managedChannel;
    }

    public RpcResponse callMethod(
            Descriptors.MethodDescriptor methodDescriptor,
            DynamicMessage message,
            Metadata headers)
            throws StatusRuntimeException {
        MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor = grpcMethodDescriptor(methodDescriptor);
        if (MethodDescriptor.MethodType.UNARY.equals(grpcMethodDescriptor.getType())) {
            RpcResponse.Builder responseBuilder = RpcResponse.builder();
            Channel finalChannel = ClientInterceptors.intercept(channel, new ServerMetadataInterceptor(responseBuilder, headers));
            DynamicMessage responseMessage = ClientCalls.blockingUnaryCall(
                    finalChannel,
                    grpcMethodDescriptor,
                    CallOptions.DEFAULT,
                    message);
            return responseBuilder.message(responseMessage).build();
        } else {
            throw new RuntimeException(String.format("Method type=[%s] is not supported", grpcMethodDescriptor.getType()));
        }
    }

    private static MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor(Descriptors.MethodDescriptor methodDescriptor) {
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                // TODO, infer type from method descriptor? Should streaming be supported?
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
                    // TODO, more details on exception
                    throw new RuntimeException("Could not parse", e);
                }

            }
        };
    }

    @Override
    public void close() {
        this.channel.shutdownNow();
    }
}
