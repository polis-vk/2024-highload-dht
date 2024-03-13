package ru.vk.itmo.test.kovalevigor.server;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public class ServerRequestValidationStrategyDecorator extends ServerStrategyDecorator {

    private static final Map<Paths, EnumSet<Parameters>> REQUIRED_PARAMETERS = Map.of(
            Paths.V0_ENTITY, EnumSet.of(Parameters.ID)
    );

    private static final Map<Paths, Set<Integer>> ALLOWED_METHODS = Map.of(
            Paths.V0_ENTITY, Set.of(Request.METHOD_GET, Request.METHOD_PUT, Request.METHOD_DELETE)
    );

    public ServerRequestValidationStrategyDecorator(ServerStrategy httpServer) {
        super(httpServer);
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        Paths path = Paths.getPath(request.getPath());
        if (path != null) {
            if (!checkMethods(request, path)) {
                session.sendResponse(Responses.NOT_ALLOWED.toResponse());
                return;
            } else if (checkParameters(request, path)) {
                super.handleRequest(request, session);
                return;
            }
        }
        handleDefault(request, session);
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
