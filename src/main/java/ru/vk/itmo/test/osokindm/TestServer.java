package ru.vk.itmo.test.osokindm;

import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class TestServer {

    private TestServer() {
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        ServiceImpl service = new ServiceImpl(new ServiceConfig(
                8080, "http://localhost",
                List.of("http://localhost"),
                Path.of("/home/john/database/")
        ));
        service.start().get();
    }

}
