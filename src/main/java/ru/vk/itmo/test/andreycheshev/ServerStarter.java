package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ServerStarter {
    private static final Path STORAGE_DIR_PATH = Path.of("/home/andrey/andrey/lab6/storage");
    private static final String LOCALHOST = "http://localhost";
    private static final int BASE_PORT = 8080;
    private static final int CLUSTER_NODE_COUNT = 4;

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        startCluster();
    }

    private static void startCluster() throws IOException, ExecutionException, InterruptedException {
        List<Integer> ports = new ArrayList<>(CLUSTER_NODE_COUNT);
        List<Path> dirs = new ArrayList<>(CLUSTER_NODE_COUNT);
        for (int i = 0; i < CLUSTER_NODE_COUNT; i++) {
            int port = BASE_PORT + i;
            Path dir = STORAGE_DIR_PATH.resolve(Paths.get(String.valueOf(port)));

            ports.add(port);
            dirs.add(dir);

            Files.createDirectories(dir);
        }

        List<String> urls = new ArrayList<>(CLUSTER_NODE_COUNT);
        for (Integer port: ports) {
            urls.add(LOCALHOST + ":" + port);
        }

        for (int i = 0; i < CLUSTER_NODE_COUNT; i++) {
            ServiceImpl service = new ServiceImpl(
                    new ServiceConfig(
                            ports.get(i),
                            urls.get(i),
                            urls,
                            dirs.get(i)
                    )
            );
            service.start().get();
        }
    }
}
