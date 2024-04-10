package ru.vk.itmo.test.kovalevigor.server;

import one.nio.server.AcceptorConfig;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.kovalevigor.config.DaoServerConfig;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerBasedOnStrategy;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerDaoStrategy;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerFull;
import ru.vk.itmo.test.kovalevigor.server.strategy.decorators.ServerOneExecutorStrategyDecorator;
import ru.vk.itmo.test.kovalevigor.server.strategy.decorators.ServerReplicationStrategyDecorator;
import ru.vk.itmo.test.kovalevigor.server.strategy.decorators.ServerRequestValidationStrategyDecorator;
import ru.vk.itmo.test.kovalevigor.server.strategy.decorators.ServerSendResponseStrategyDecorator;
import ru.vk.itmo.test.kovalevigor.server.util.ServerUtil;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ru.vk.itmo.test.kovalevigor.server.util.ServerUtil.REMOTE_TIMEOUT;

public class ServiceImpl implements Service {

    public static final long FLUSH_THRESHOLD_BYTES = 128 * 1024 * 1024 / 3 * 8;
    public static final int HTTP_EXECUTOR_SIZE = 4;
    private ServerFull server;
    private FullServiceInfo fullServiceInfo;
    public final DaoServerConfig config;
    private HttpClient httpClient;
    private ExecutorService httpExecutor;

    public ServiceImpl(ServiceConfig config, long flushThresholdBytes) {
        this.config = mapConfig(config);
        this.config.flushThresholdBytes = flushThresholdBytes;
    }

    public ServiceImpl(ServiceConfig config) {
        this(config, FLUSH_THRESHOLD_BYTES);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        httpExecutor = Executors.newFixedThreadPool(HTTP_EXECUTOR_SIZE);
        httpClient = HttpClient.newBuilder()
                .executor(httpExecutor)
                .connectTimeout(REMOTE_TIMEOUT)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        fullServiceInfo = new FullServiceInfo(
                httpClient,
                config.clusterUrls,
                config.selfUrl
        );
        server = new ServerBasedOnStrategy(
                config,
                new ServerOneExecutorStrategyDecorator(
                        new ServerSendResponseStrategyDecorator(
                            new ServerRequestValidationStrategyDecorator(
                                    new ServerReplicationStrategyDecorator(
                                        new ServerDaoStrategy(config),
                                        fullServiceInfo
                                    )
                            )
                        ),
                        config.corePoolSize, config.maximumPoolSize,
                        config.keepAliveTime, config.queueCapacity
                )
        );
        return CompletableFuture.runAsync(server::start);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (server != null) {
            ServerUtil.shutdownAndAwaitTermination(httpExecutor);
            httpClient.close();
            server.stop();
            server.close();
            fullServiceInfo.close();
        }
        return CompletableFuture.completedFuture(null);
    }

    private static AcceptorConfig[] mapAcceptors(ServiceConfig config) {
        AcceptorConfig acceptorConfig = new AcceptorConfig();
        acceptorConfig.port = config.selfPort();
        acceptorConfig.reusePort = true;
        return new AcceptorConfig[]{acceptorConfig};
    }

    private static DaoServerConfig mapConfig(ServiceConfig config) {
        DaoServerConfig serverConfig = new DaoServerConfig();
        serverConfig.basePath = config.workingDir();
        serverConfig.acceptors = mapAcceptors(config);
        serverConfig.closeSessions = true;
        serverConfig.clusterUrls = config.clusterUrls();
        serverConfig.selfUrl = config.selfUrl();

        return serverConfig;
    }
}
