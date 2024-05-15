package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

@ServiceFactory(stage = 7)
public class ZstdCompressedLSMServiceFactoryImpl implements ServiceFactory.Factory {
    @Override
    public Service create(ServiceConfig config) {
        return new LSMServiceImpl(
                config,
                new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.ZSTD, 4096)
        );
    }
}
