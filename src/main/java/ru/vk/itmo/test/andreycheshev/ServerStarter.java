package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ServerStarter {
    private static final Path STORAGE_DIR_PATH = Path.of("/home/andrey/andrey/lab3/");
    private static final String LOCALHOST = "http://localhost";
    private static final int PORT = 8080;
    private static final int CLUSTER_NODE_COUNT = 4;

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
//        startSingle();
        startCluster();
    }

    private static void startSingle() throws IOException, ExecutionException, InterruptedException {
        ServiceImpl service = new ServiceImpl(
                new ServiceConfig(
                        PORT,
                        LOCALHOST,
                        List.of(LOCALHOST),
                        STORAGE_DIR_PATH
                )
        );
        service.start().get();
    }

    private static void startCluster() throws IOException, ExecutionException, InterruptedException {
        List<Integer> ports = new ArrayList<>(CLUSTER_NODE_COUNT);
        List<Path> dirs = new ArrayList<>(CLUSTER_NODE_COUNT);
        for (int i = 0; i < CLUSTER_NODE_COUNT; i++) {
            int port = 8080 + i;
            Path dir = STORAGE_DIR_PATH.resolve(Paths.get(String.valueOf(port)));

            ports.add(port);
            dirs.add(dir);

            try {
                Files.createDirectory(dir);
            } catch (FileSystemAlreadyExistsException ignored) {
                clearDir(dir);
            }
        }

        List<String> urls = new ArrayList<>(CLUSTER_NODE_COUNT);
        for (Integer port: ports) {
            urls.add(STR."\{LOCALHOST}:\{port}");
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

    private static void clearDir(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc == null) {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                } else {
                    throw exc;
                }
            }
        });
    }
}
