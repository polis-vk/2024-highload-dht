package ru.vk.itmo.test.kovalevigor.server.util;

public enum Paths {
    V0_ENTITY("/v0/entity");

    public final String path;

    Paths(String path) {
        this.path = path;
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
}
