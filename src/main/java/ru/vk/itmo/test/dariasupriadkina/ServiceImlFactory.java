package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.dariasupriadkina.sharding.RendezvousHashing;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.CustomThreadConfig;

import java.nio.file.Path;

@ServiceFactory(stage = 6)
public class ServiceImlFactory implements ServiceFactory.Factory {

    public static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024;

    @Override
    public Service create(ServiceConfig serviceConfig) {
        ShardingPolicy shardingPolicy = new RendezvousHashing(
                serviceConfig.clusterUrls()
        );
        Config referenceDaoConfig = new Config(Path.of(serviceConfig.workingDir().toUri()), FLUSH_THRESHOLD_BYTES);
        CustomThreadConfig workerConfig = CustomThreadConfig.baseConfig("worker-thread");
        CustomThreadConfig nodeConfig = CustomThreadConfig.baseConfig("node-thread");
        return new ServiceIml(serviceConfig, referenceDaoConfig, workerConfig, shardingPolicy, nodeConfig);
    }
}
