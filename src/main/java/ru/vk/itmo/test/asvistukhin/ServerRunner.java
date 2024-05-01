package ru.vk.itmo.test.asvistukhin;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ServerRunner {

    private ServerRunner() {
        // hello, codeclimate
    }

    public static void main(
        String[] args
    ) throws IOException, ExecutionException, InterruptedException, TimeoutException {
        ServiceImpl serviceImpl = createService();
        serviceImpl.start().get(10, TimeUnit.SECONDS);
    }

    private static ServiceImpl createService() throws IOException {
        return new ServiceImpl(
            new ServiceConfig(
                8080,
                "http://localhost:8080",
                List.of("http://localhost:8080"),
                Files.createTempDirectory("nio-server")
            )
        );
    }
}
