package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.asvistukhin.dao.PersistentDao;
import ru.vk.itmo.test.asvistukhin.dao.TimestampEntry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig serviceConfig;
    private ProxyRequestHandler proxyRequestHandler;
    private PersistentDao dao;
    private ServerImpl server;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new PersistentDao(new Config(serviceConfig.workingDir(), 1024 * 1024 * 5L));
        server = new ServerImpl(serviceConfig);
        proxyRequestHandler = new ProxyRequestHandler(serviceConfig);

        server.addRequestHandlers(this);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        proxyRequestHandler.close();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public Response get(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (proxyRequestHandler.isNeedProxy(id)) {
            return proxyRequestHandler.proxyRequest(request);
        }

        Entry<MemorySegment> entry = dao.get(MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }

        return new Response(Response.OK, entry.value().toArray(ValueLayout.JAVA_BYTE));
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public Response upsert(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (proxyRequestHandler.isNeedProxy(id)) {
            return proxyRequestHandler.proxyRequest(request);
        }

        dao.upsert(
            new TimestampEntry<>(
                MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                MemorySegment.ofArray(request.getBody()),
                Instant.now().toEpochMilli()
            )
        );

        return new Response(Response.CREATED, Response.EMPTY);
    }

    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public Response delete(@Param(value = "id", required = true) String id, Request request) {
        if (id.isEmpty()) {
            return new Response(Response.BAD_REQUEST, Response.EMPTY);
        }

        if (proxyRequestHandler.isNeedProxy(id)) {
            return proxyRequestHandler.proxyRequest(request);
        }

        dao.upsert(
            new TimestampEntry<>(
                MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8)),
                null,
                Instant.now().toEpochMilli()
            )
        );

        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
