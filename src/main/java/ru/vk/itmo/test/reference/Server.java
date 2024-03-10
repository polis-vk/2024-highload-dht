package ru.vk.itmo.test.reference;

import one.nio.async.CustomThreadFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Server {

    private static final int THREADS = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws IOException {
        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1024);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS,
            1000, TimeUnit.SECONDS,
            queue,
            new CustomThreadFactory("worker", true),
            new ThreadPoolExecutor.AbortPolicy());
        ReferenceServer server = new ReferenceServer(new ServiceConfig(
            8080, "http://localhost",
            List.of("http://localhost"),
            Paths.get("tmp/db")
        ), executor, new ReferenceDao(new Config(
            Paths.get("tmp/db"),
            1024 * 1024)
        ));
        server.start();
    }
}
