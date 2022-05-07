package naply.grpc_banter;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.ProtocolStringList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileDescriptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileDescriptorRegistry.class);

    private final Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName;
    private final Map<String, Descriptors.Descriptor> messageTypesByFullName;
    private final Map<String, Descriptors.EnumDescriptor> enumTypesByFullName;

    public FileDescriptorRegistry(
            Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName,
            Map<String, Descriptors.Descriptor> messageTypesByFullName,
            Map<String, Descriptors.EnumDescriptor> enumTypesByFullName) {
        this.serviceDescriptorsByFullName = serviceDescriptorsByFullName;
        this.messageTypesByFullName = messageTypesByFullName;
        this.enumTypesByFullName = enumTypesByFullName;
    }

    public Descriptors.ServiceDescriptor findServiceByName(String name) {
        if (serviceDescriptorsByFullName.containsKey(name))
            return serviceDescriptorsByFullName.get(name);
        throw new ServiceResolutionError(String.format("Service [%s] not found", name));
    }

    public Collection<Descriptors.ServiceDescriptor> getAllServices() {
        return serviceDescriptorsByFullName.values();
    }

    public Descriptors.Descriptor findMessageTypeByFullName(String fullName) {
        return messageTypesByFullName.get(fullName);
    }

    public static FileDescriptorRegistry fromFileDescriptorSet(String fileName) {
        File file = new File(fileName);
        DescriptorProtos.FileDescriptorSet fds;
        try (FileInputStream stream = new FileInputStream(file)){
            fds = DescriptorProtos.FileDescriptorSet.parseFrom(stream);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Could not read=[%s]", file.getAbsoluteFile()), e);
        }
        return fromFileDescriptorSet(fds);
    }

    public static FileDescriptorRegistry fromFileDescriptorSet(DescriptorProtos.FileDescriptorSet fds) {
        Map<String, Descriptors.FileDescriptor> fileDescriptorsByName = buildFileDescriptors(fds);

        List<Descriptors.ServiceDescriptor> allServices = new ArrayList<>();
        List<Descriptors.Descriptor> allMessageTypes = new ArrayList<>();
        List<Descriptors.EnumDescriptor> allEnumTypes = new ArrayList<>();
        for (Descriptors.FileDescriptor fd : fileDescriptorsByName.values()) {
            List<Descriptors.Descriptor> messageTypes = fd.getMessageTypes().stream()
                    .flatMap(FileDescriptorRegistry::getNestedMessageTypes)
                    .collect(Collectors.toList());
            if (log.isDebugEnabled()) {
                log.debug("fileDescriptor=[{}] has services={} messageTypes={} enumTypes={}",
                        fd.getFullName(),
                        fd.getServices().stream().map(Descriptors.ServiceDescriptor::getFullName).collect(Collectors.toList()),
                        messageTypes.stream().map(Descriptors.Descriptor::getFullName).collect(Collectors.toList()),
                        fd.getEnumTypes().stream().map(Descriptors.EnumDescriptor::getFullName).collect(Collectors.toList()));
            }
            allServices.addAll(fd.getServices());
            allMessageTypes.addAll(messageTypes);
            allEnumTypes.addAll(fd.getEnumTypes());
        }

        Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName = allServices.stream()
                .collect(Collectors.toMap(Descriptors.ServiceDescriptor::getFullName, v -> v));

        Map<String, Descriptors.Descriptor> messageTypesByFullName = allMessageTypes.stream()
                .collect(Collectors.toMap(Descriptors.Descriptor::getFullName, v -> v));

        Map<String, Descriptors.EnumDescriptor> enumTypesByFullName = allEnumTypes.stream()
                .collect(Collectors.toMap(Descriptors.EnumDescriptor::getFullName, v -> v));

        return new FileDescriptorRegistry(
                serviceDescriptorsByFullName,
                messageTypesByFullName,
                enumTypesByFullName);
    }

    private static Stream<Descriptors.Descriptor> getNestedMessageTypes(Descriptors.Descriptor messageType) {
        return Stream.concat(
                Stream.of(messageType),
                messageType.getNestedTypes().stream()
                        .flatMap(FileDescriptorRegistry::getNestedMessageTypes)
        );
    }
    
    private static Map<String, Descriptors.FileDescriptor> buildFileDescriptors(DescriptorProtos.FileDescriptorSet fds) {
        HashMap<String, Descriptors.FileDescriptor> result = new HashMap<>();
        int lastSize = 0;
        // Loop over FileDescriptorProtos until all are resolved w/ dependencies
        // TODO: optimize by building a DAG
        while (result.size() < fds.getFileList().size()) {
            for (DescriptorProtos.FileDescriptorProto fdp : fds.getFileList()) {
                // Skip if already resolved
                if (result.containsKey(fdp.getName())) continue;
                // Build dependencies
                ProtocolStringList dependencyList = fdp.getDependencyList();
                if (result.keySet().containsAll(dependencyList)) {
                    Descriptors.FileDescriptor[] dependencies = new Descriptors.FileDescriptor[dependencyList.size()];
                    for (int i = 0; i < dependencyList.size(); i++) {
                        dependencies[i] = result.get(dependencyList.get(i));
                    }
                    Descriptors.FileDescriptor fileDescriptor;
                    try {
                        fileDescriptor = Descriptors.FileDescriptor.buildFrom(fdp, dependencies);
                    } catch (Descriptors.DescriptorValidationException e) {
                        // TODO more informative error
                        throw new RuntimeException("Validation error!", e);
                    }
                    log.debug("Resolved FileDescriptor=[{}] with dependencies=[{}]", fdp.getName(), dependencyList);
                    result.put(fdp.getName(), fileDescriptor);
                }
            }
            if (lastSize == result.size()) {
                throw new RuntimeException("Deadlock detected");
            }
            lastSize = result.size();
        }
        return result;
    }

    public static class ServiceResolutionError extends RuntimeException {
        public ServiceResolutionError(String message) {
            super(message);
        }
    }

    public String toString() {
        return "FileDescriptorSet{services=" +
                serviceDescriptorsByFullName.keySet() +
                " messageTypes=" +
                messageTypesByFullName.keySet() +
                " enumTypes=" +
                enumTypesByFullName.keySet() +
                "}";
    }
}
