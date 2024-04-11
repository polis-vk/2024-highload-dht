package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.dariasupriadkina.sharding.RendezvousHashing;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerConfig;

import java.nio.file.Path;

@ServiceFactory(stage = 5)
public class ServiceImlFactory implements ServiceFactory.Factory {

    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024;
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int MAXIMUM_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int QUEUE_SIZE = 1024;
    private static final int SHUTDOWN_TIMEOUT_SEC = 30;

    @Override
    public Service create(ServiceConfig serviceConfig) {
        ShardingPolicy shardingPolicy = new RendezvousHashing(
                serviceConfig.clusterUrls()
        );
        Config referenceDaoConfig = new Config(Path.of(serviceConfig.workingDir().toUri()), FLUSH_THRESHOLD_BYTES);
        WorkerConfig workerConfig = new WorkerConfig(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                QUEUE_SIZE, SHUTDOWN_TIMEOUT_SEC);
        return new ServiceIml(serviceConfig, referenceDaoConfig, workerConfig, shardingPolicy);
    }
}
