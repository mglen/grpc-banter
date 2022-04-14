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

public class FileDescriptorRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileDescriptorRegistry.class);

    // Full names for a `service ServiceName {}` grpc service. Generally should only be one, this is purely a convenience
    // to let the client specify service by short name.
    // TODO: this feature is not necessary, should be cut.
    private final Map<String, Set<String>> serviceFullNamesByShortName;
    private final Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName;
    private final Map<String, Descriptors.Descriptor> messageTypesByFullName;
    private final Map<String, Descriptors.EnumDescriptor> enumTypesByFullName;

    public FileDescriptorRegistry(
            Map<String, Set<String>> serviceFullNamesByShortName,
            Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName,
            Map<String, Descriptors.Descriptor> messageTypesByFullName,
            Map<String, Descriptors.EnumDescriptor> enumTypesByFullName) {
        this.serviceFullNamesByShortName = serviceFullNamesByShortName;
        this.serviceDescriptorsByFullName = serviceDescriptorsByFullName;
        this.messageTypesByFullName = messageTypesByFullName;
        this.enumTypesByFullName = enumTypesByFullName;
    }

    public Descriptors.ServiceDescriptor findServiceByName(String name) {
        if (serviceDescriptorsByFullName.containsKey(name))
            return serviceDescriptorsByFullName.get(name);
        Set<String> packages = serviceFullNamesByShortName.get(name);
        if (packages == null || packages.isEmpty()) {
            throw new ServiceResolutionError(String.format("Service [%s] not found", name));
        } else if (packages.size() > 1) {
            throw new ServiceResolutionError(String.format(
                    "Found matching service [%s] under multiple packages=%s." +
                            "You must specify the full service path",
                    name, packages));
        } else {
            // Single match
            return serviceDescriptorsByFullName.get(packages.iterator().next());
        }
    }

    public Descriptors.Descriptor findMessageTypeByFullName(String fullName) {
        return messageTypesByFullName.get(fullName);
    }

    public Descriptors.EnumDescriptor findEnumTypeByFullName(String fullName) {
        return enumTypesByFullName.get(fullName);
    }

    public static FileDescriptorRegistry fromFileDescriptorSetFile(String fileName) {
        File file = new File(fileName);
        DescriptorProtos.FileDescriptorSet fds;
        try {
            fds = DescriptorProtos.FileDescriptorSet.parseFrom(new FileInputStream(file));
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
            List<Descriptors.Descriptor> messageTypes = getNestedMessageTypes(fd.getMessageTypes());
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

        Map<String, Descriptors.ServiceDescriptor> serviceDescriptorsByFullName = new HashMap<>();
        Map<String, Set<String>> serviceFullNamesByShortName = new HashMap<>();
        for (Descriptors.ServiceDescriptor service : allServices) {
            serviceDescriptorsByFullName.put(service.getFullName(), service);
            serviceFullNamesByShortName.compute(service.getName(), (k, v) -> {
                Set<String> fullNames = v == null ? new HashSet<>() : v;
                fullNames.add(service.getFullName());
                return fullNames;
            });
        }

        Map<String, Descriptors.Descriptor> messageTypesByFullName = allMessageTypes.stream()
                .collect(Collectors.toMap(Descriptors.Descriptor::getFullName, v -> v));

        Map<String, Descriptors.EnumDescriptor> enumTypesByFullName = allEnumTypes.stream()
                .collect(Collectors.toMap(Descriptors.EnumDescriptor::getFullName, v -> v));

        return new FileDescriptorRegistry(
                serviceFullNamesByShortName,
                serviceDescriptorsByFullName,
                messageTypesByFullName,
                enumTypesByFullName);
    }

    private static List<Descriptors.Descriptor> getNestedMessageTypes(List<Descriptors.Descriptor> messageTypes) {
        List<Descriptors.Descriptor> finalMessageTypes = new ArrayList<>(messageTypes);
        for (Descriptors.Descriptor messageType : messageTypes) {
            finalMessageTypes.addAll(messageType.getNestedTypes());
        }
        return finalMessageTypes;
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
        return "services=" +
                serviceDescriptorsByFullName.keySet() +
                " messageTypes=" +
                messageTypesByFullName.keySet() +
                " enumTypes=" +
                enumTypesByFullName.keySet();
    }
}
