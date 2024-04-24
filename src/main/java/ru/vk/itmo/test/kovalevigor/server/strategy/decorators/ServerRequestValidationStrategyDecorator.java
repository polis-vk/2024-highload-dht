package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;
import ru.vk.itmo.test.kovalevigor.server.util.Paths;
import ru.vk.itmo.test.kovalevigor.server.util.Responses;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ServerRequestValidationStrategyDecorator extends ServerStrategyDecorator {

    private static final Map<Paths, EnumSet<Parameters>> REQUIRED_PARAMETERS = Map.of(
            Paths.V0_ENTITY, EnumSet.of(Parameters.ID),
            Paths.V0_ENTITIES, EnumSet.of(Parameters.START)
    );

    private static final Map<Paths, Set<Integer>> ALLOWED_METHODS = Map.of(
            Paths.V0_ENTITY, Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE),
            Paths.V0_ENTITIES, Set.of(Request.METHOD_GET)
    );

    public ServerRequestValidationStrategyDecorator(ServerStrategy httpServer) {
        super(httpServer);
    }

    @Override
    public Response handleRequest(Request request, HttpSession session) throws IOException {
        Response response = checkErrors(request);
        return response == null
                ? super.handleRequest(request, session)
                : response;
    }

    @Override
    public CompletableFuture<Response> handleRequestAsync(
            Request request,
            HttpSession session,
            Executor executor
    ) {
        Response response = checkErrors(request);
        return response == null
                ? super.handleRequestAsync(request, session, executor)
                : CompletableFuture.completedFuture(response);
    }

    private static Response checkErrors(Request request) {
        Paths path = Paths.getPath(request.getPath());
        if (path != null) {
            if (checkMethods(request, path)) {
                if (checkParameters(request, path)) {
                    return null;
                }
            } else {
                return Responses.NOT_ALLOWED.toResponse();
            }
        }
        return Responses.BAD_REQUEST.toResponse();
    }

    private static boolean checkMethods(Request request, Paths path) {
        Set<Integer> methods = ALLOWED_METHODS.get(path);
        return methods == null || methods.contains(request.getMethod());
    }

    private static boolean checkParameters(Request request, Paths path) {
        EnumSet<Parameters> parameters = REQUIRED_PARAMETERS.get(path);
        if (parameters == null) {
            return true;
        }
        for (Parameters parameter : parameters) {
            String value = Parameters.getParameter(request, parameter);
            if (value == null || value.isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
