package ru.vk.itmo.test.viktorkorotkikh;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

public final class LSMServiceFactories {
    private LSMServiceFactories() {
    }

    @ServiceFactory(stage = 7)
    public static class LSMServiceFactoryImpl implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new LSMServiceImpl(config);
        }
    }

    @ServiceFactory(stage = 7)
    public static class LZ4CompressedLSMServiceFactoryImpl implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new LSMServiceImpl(
                    config,
                    new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.LZ4, 4096)
            );
        }
    }

    @ServiceFactory(stage = 7)
    public static class ZstdCompressedLSMServiceFactoryImpl implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new LSMServiceImpl(
                    config,
                    new Config.CompressionConfig(true, Config.CompressionConfig.Compressor.ZSTD, 4096)
            );
        }
    }
}
