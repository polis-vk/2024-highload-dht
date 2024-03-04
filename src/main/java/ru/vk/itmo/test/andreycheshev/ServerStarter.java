package ru.vk.itmo.test.andreycheshev;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class ServerStarter {
    private static final Path STORAGE_DIR_PATH = Path.of("/home/andrey/andrey/get_storage");
    private static final String SELF_URL = "http://localhost";
    private static final int PORT = 8080;

    private ServerStarter() {

    }

    public static void main(String[] args) throws IOException {
        ServiceImpl service = new ServiceImpl(
                new ServiceConfig(
                        PORT,
                        SELF_URL,
                        List.of(SELF_URL),
                        STORAGE_DIR_PATH
                )
        );

        try {
            service.start().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
