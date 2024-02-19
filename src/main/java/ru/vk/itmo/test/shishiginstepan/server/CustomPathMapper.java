package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.http.PathMapper;
import one.nio.http.RequestHandler;

public class CustomPathMapper extends PathMapper {
    public static class IllegalMethodException extends RuntimeException {
    }

    @Override
    public RequestHandler find(String path, int method) {
        RequestHandler[] handlersByMethod = (RequestHandler[]) super.get(path);
        if (handlersByMethod == null) {
            return null;
        } else {
            if (method > 0 && method < handlersByMethod.length && handlersByMethod[method] != null)
                return handlersByMethod[method];
            else throw new IllegalMethodException();
        }
    }
}
