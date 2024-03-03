package ru.vk.itmo.test.andreycheshev;



import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ServerStarter {
    private static final Path STORAGE_DIR_PATH = Path.of("/home/andrey/andrey/tmp");
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
        service.start();
    }
}
