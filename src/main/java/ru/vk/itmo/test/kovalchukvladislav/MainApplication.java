package ru.vk.itmo.test.kovalchukvladislav;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;

public final class MainApplication {
    private static final int SELF_PORT = 8080;
    private static final Path WORKING_DIR = createPath();
    private static final String SELF_URL = "http://localhost";
    private static final String WORKING_DIR_NAME = "serviceData";
    private static final List<String> CLUSTER_URLS = List.of(SELF_URL);
    private static final ServiceConfig SERVICE_CONFIG = new ServiceConfig(SELF_PORT, SELF_URL, CLUSTER_URLS, WORKING_DIR);

    private static Path createPath() {
        try {
            return Files.createTempDirectory(WORKING_DIR_NAME);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        Service service = new ServiceImpl(SERVICE_CONFIG);
        service.start().get();
    }
}
