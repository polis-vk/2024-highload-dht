package ru.vk.itmo;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface Service {
    CompletableFuture<?> start() throws IOException;

    CompletableFuture<?> stop() throws IOException;
}
