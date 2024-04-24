package ru.vk.itmo.test.kovalevigor.server.strategy.util;

public enum Paths {
    V0_ENTITY("/v0/entity"),
    V0_ENTITIES("/v0/entities", true);

    public final String path;
    private final boolean isLocal;

    Paths(String path, boolean isLocal) {
        this.path = path;
        this.isLocal = isLocal;
    }

    Paths(String path) {
        this(path, false);
    }

    public static Paths getPath(String request) {
        for (Paths path : Paths.values()) {
            if (request.startsWith(path.path)) {
                return path;
            }
        }
        return null;
    }

    public static Paths getPathOrThrow(String request) {
        for (Paths path : Paths.values()) {
            if (request.startsWith(path.path)) {
                return path;
            }
        }
        throw new IllegalStateException("Unexpected path");
    }

    public boolean isLocal() {
        return this.isLocal;
    }
}
