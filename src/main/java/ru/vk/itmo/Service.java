package ru.vk.itmo;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public interface Service {
    CompletableFuture<Void> start() throws IOException;

    CompletableFuture<Void> stop() throws IOException;
}
