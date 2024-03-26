package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Request;

public class RequestParameters {
    private static final String ID_PARAMETER_NAME = "id=";
    public static final String FROM_PARAMETER_NAME = "from=";
    public static final String ACK_PARAMETER_NAME = "ack=";

    public final String id;
    public final int from;
    public final int ack;

    public RequestParameters(Request request, int clusterSize) throws IllegalArgumentException {
        String idString = request.getParameter(ID_PARAMETER_NAME);
        String fromString = request.getParameter(FROM_PARAMETER_NAME);
        String ackString = request.getParameter(ACK_PARAMETER_NAME);
        if (isEmptyParam(idString)) throw new IllegalArgumentException();

        id = idString;
        try {
            from = isEmptyParam(fromString) ? clusterSize : Integer.parseInt(fromString);
            ack = isEmptyParam(ackString) ? (from + 1) / 2 : Integer.parseInt(ackString);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException();
        }

        if (from <= 0 || clusterSize < from || from < ack) {
            throw new IllegalArgumentException();
        }
    }

    public static boolean isEmptyParam(String param) {
        return param == null || param.isEmpty();
    }

    public static boolean isEmptyRequest(Request request) {
        return request.getBody() == null;
    }
}
