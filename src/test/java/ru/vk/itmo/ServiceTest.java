package ru.vk.itmo;

import ru.vk.itmo.test.ServiceFactory;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Target({ElementType.ANNOTATION_TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ParameterizedTest
@ArgumentsSource(ServiceTest.ServiceList.class)
@ExtendWith(ServiceTest.ServiceList.class)
@Timeout(value = 1, unit = TimeUnit.MINUTES)
public @interface ServiceTest {
    int stage();
    int clusterSize() default 1;

    class ServiceList implements ArgumentsProvider, ExecutionCondition {
        private static final AtomicInteger ID = new AtomicInteger();
        private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create("service");
        private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

        private List<Class<?>> getFactories(ExtensionContext context) throws Exception {
            if (context.getStore(NAMESPACE).get("factories") == null) {
                CodeSource codeSource = ServiceFactory.class.getProtectionDomain().getCodeSource();
                Path path = Path.of(codeSource.getLocation().toURI());
                try (Stream<Path> walk = Files.walk(path)) {
                    List<Class<?>> factories = walk
                            .filter(p -> p.getFileName().toString().endsWith(".class"))
                            .map(p -> getServiceClass(path, p))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());

                    List<Class<?>> maxFactories = new ArrayList<>();
                    int maxStage = 0;
                    for (Class<?> factory : factories) {
                        ServiceFactory annotation = factory.getAnnotation(ServiceFactory.class);
                        int stage = annotation.stage();
                        if (stage < maxStage) {
                            continue;
                        }
                        if (stage > maxStage) {
                            maxStage = stage;
                            maxFactories.clear();
                        }
                        maxFactories.add(factory);
                    }

                    if (maxFactories.isEmpty()) {
                        throw new IllegalStateException("No Factory declared under ru.vk.itmo.test.<username> package");
                    }
                    context.getStore(NAMESPACE).put("factories", maxFactories);
                }
            }
            //noinspection unchecked
            return (List<Class<?>>) context.getStore(NAMESPACE).get("factories");
        }

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            List<Class<?>> maxFactories = getFactories(context);

            if (maxFactories.isEmpty()) {
                throw new IllegalStateException("No Factory declared under ru.vk.itmo.test.<username> package");
            }

            return maxFactories.stream().map(c -> {
                List<ServiceInfo> services;
                try {
                    services = createServices(context, c);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                try {
                    Type parameterType = context.getRequiredTestMethod().getGenericParameterTypes()[0];
                    if (parameterType == ServiceInfo.class) {
                        ServiceInfo service = services.getFirst();
                        return Arguments.of(service);
                    } else if (parameterType instanceof ParameterizedType
                            && ((ParameterizedType) parameterType).getRawType() == List.class
                            && ((ParameterizedType) parameterType).getActualTypeArguments()[0] == ServiceInfo.class) {
                        return Arguments.of(services);
                    }
                    throw new IllegalArgumentException(STR."Unknown type: \{parameterType}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        private Class<?> getServiceClass(Path path, Path p) {
            StringBuilder result = new StringBuilder();
            for (Path subPath : path.relativize(p)) {
                result.append(subPath).append(".");
            }
            String className = result.substring(0, result.length() - ".class.".length());
            Class<?> clazz;
            try {
                clazz = Class.forName(className, false, ServiceFactory.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            if (clazz.getAnnotation(ServiceFactory.class) == null) {
                return null;
            }
            if (!clazz.getPackageName().startsWith("ru.vk.itmo.test.")) {
                throw new IllegalArgumentException("ServiceFactory should be under package ru.vk.itmo.test.<username>");
            }
            return clazz;
        }

        private List<ServiceInfo> createServices(ExtensionContext context, Class<?> clazz) throws IOException, InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
            ServiceTest annotation = context.getRequiredTestMethod().getAnnotation(ServiceTest.class);
            ServiceFactory.Factory f = (ServiceFactory.Factory) clazz.getDeclaredConstructor().newInstance();

            int[] ports = randomPorts(annotation.clusterSize());
            Arrays.sort(ports);
            List<String> cluster = new ArrayList<>(annotation.clusterSize());
            for (int port : ports) {
                cluster.add(STR."http://localhost:\{port}");
            }

            List<ServiceInfo> services = new ArrayList<>(annotation.clusterSize());
            for (int i = 0; i < annotation.clusterSize(); i++) {
                Path workingDir = Files.createTempDirectory(STR."service\{i}");

                ServiceConfig config = new ServiceConfig(ports[i], cluster.get(i), cluster, workingDir);
                Service service = f.create(config);

                ExtensionContext.Store.CloseableResource res = () -> {
                    service.stop().get(10, TimeUnit.MINUTES);
                    FileUtils.delete(workingDir);
                };

                context.getStore(NAMESPACE).put(STR."\{ID.incrementAndGet()}", res);
                services.add(new ServiceInfo(service, config, HTTP_CLIENT));
            }

            for (ServiceInfo service : services) {
                try {
                    service.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }

            return services;
        }

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            try {
                List<Class<?>> factories = getFactories(context);
                if (factories.isEmpty()) {
                    throw new IllegalStateException("No Factory declared under ru.vk.itmo.test.<username> package");
                }
                ServiceTest test = context.getRequiredTestMethod().getAnnotation(ServiceTest.class);
                int minStage = test.stage();
                ServiceFactory firstFactoryAnnotations = factories.getFirst().getAnnotation(ServiceFactory.class);
                if (minStage > firstFactoryAnnotations.stage()) {
                    return ConditionEvaluationResult.disabled("Implementation is not ready");
                }

                return ConditionEvaluationResult.enabled("Implementation is ready");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static int[] randomPorts(int count) {
            List<ServerSocket> sockets = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                try {
                    sockets.add(new ServerSocket());
                } catch (IOException e) {
                    throw closeAndRethrow(sockets, e);
                }
            }

            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                try {
                    ServerSocket socket = sockets.get(i);
                    socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 1);
                    ports[i] = socket.getLocalPort();
                } catch (IOException e) {
                    throw closeAndRethrow(sockets, e);
                }
            }

            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    throw closeAndRethrow(sockets, e);
                }
            }

            return ports;
        }

        private static RuntimeException closeAndRethrow(List<ServerSocket> sockets, IOException e) {
            UncheckedIOException ex = new UncheckedIOException("Can't discover a free port", e);
            for (ServerSocket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e2) {
                    ex.addSuppressed(e2);
                }
            }
            throw ex;
        }
    }
}
