package naply.grpc_banter;

import clojure.lang.Associative;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
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
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class Client implements Closeable {

    private final FileDescriptorRegistry fileDescriptorRegistry;
    private final ManagedChannel channel;
    private final MessageConverter.Config config;

    public static Client create(Map<Keyword, Object> config) {
        String fileDescriptorSetPath = (String) config.get(Keyword.intern("file-descriptor-set"));
        String target = (String) config.get(Keyword.intern("target"));
        MessageConverter.Config converterConfig = new MessageConverter.Config(config);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target)
                .usePlaintext()
                .build();
        FileDescriptorRegistry fdr = FileDescriptorRegistry.fromFileDescriptorSetFile(fileDescriptorSetPath);
        return new Client(fdr, channel, converterConfig);
    }

    public Client(FileDescriptorRegistry fileDescriptorRegistry,
                  ManagedChannel managedChannel,
                  MessageConverter.Config config) {
        this.fileDescriptorRegistry = fileDescriptorRegistry;
        this.channel = managedChannel;
        this.config = config;
    }

    public PersistentHashMap callMethod(String serviceName, String methodName, Associative message) {
        Descriptors.MethodDescriptor methodDescriptor = getRequestMethod(serviceName, methodName);
        DynamicMessage requestMessage = MessageConverter.cljToMessage(methodDescriptor.getInputType(), message, config);
        RpcResponse response;
        try {
            response = internalCall(grpcMethodDescriptor(methodDescriptor), requestMessage);
        } catch (StatusRuntimeException e) {
            throw MessageConverter.statusRuntimeExceptionToExceptionInfo(e, config);
        }
        return MessageConverter.responseToClj(methodDescriptor.getOutputType(), response, config);
    }

    public Descriptors.Descriptor getRequestProto(String serviceName, String methodName) {
        return getRequestMethod(serviceName, methodName).getInputType();
    }

    public Set<String> getAllServicesMethods() {
        return fileDescriptorRegistry.getAllServices().stream()
                .flatMap(s -> s.getMethods().stream()
                        .map(m -> s.getFullName() + "/" + m.getName()))
                .collect(Collectors.toSet());
    }

    private Descriptors.MethodDescriptor getRequestMethod(String serviceName, String methodName) {
        Descriptors.ServiceDescriptor serviceByName = fileDescriptorRegistry.findServiceByName(serviceName);
        Descriptors.MethodDescriptor methodDescriptor = serviceByName.findMethodByName(methodName);
        if (methodDescriptor == null) {
            throw new RuntimeException(String.format("Method=[%s] not found", methodName));
        }
        return methodDescriptor;
    }

    private RpcResponse internalCall(
            MethodDescriptor<DynamicMessage, DynamicMessage> methodDescriptor,
            DynamicMessage message) {
        if (MethodDescriptor.MethodType.UNARY.equals(methodDescriptor.getType())) {
            RpcResponse.Builder responseBuilder = RpcResponse.builder();
            Channel finalChannel = ClientInterceptors.intercept(channel, ServerMetadataInterceptor.create(responseBuilder));
            DynamicMessage responseMessage = ClientCalls.blockingUnaryCall(
                    finalChannel,
                    methodDescriptor,
                    CallOptions.DEFAULT,
                    message);
            return responseBuilder.message(responseMessage).build();
        } else {
            throw new RuntimeException(String.format("Method type=[%s] is not supported", methodDescriptor.getType()));
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
