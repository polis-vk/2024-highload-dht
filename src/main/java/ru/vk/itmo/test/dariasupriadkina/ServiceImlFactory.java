package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerConfig;

import java.nio.file.Path;

@ServiceFactory(stage = 2)
public class ServiceImlFactory implements ServiceFactory.Factory {

    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024;

    //    Оптималные значения будут подбираться в ходе выполнения отчета
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAXIMUM_POOL_SIZE = 10;
    private static final int QUEUE_SIZE = 100;
    private static final int SHUTDOWN_TIMEOUT_SEC = 30;

    @Override
    public Service create(ServiceConfig serviceConfig) {
        Config referenceDaoConfig = new Config(Path.of(serviceConfig.workingDir().toUri()), FLUSH_THRESHOLD_BYTES);
        WorkerConfig workerConfig = new WorkerConfig(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE,
                QUEUE_SIZE, SHUTDOWN_TIMEOUT_SEC);
        return new ServiceIml(serviceConfig, referenceDaoConfig, workerConfig);
    }
}
