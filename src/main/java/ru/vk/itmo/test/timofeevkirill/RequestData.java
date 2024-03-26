package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Request;

public class RequestData {
    private static final String ID_PARAMETER_NAME = "id=";
    private static final String FROM_PARAMETER_NAME = "from=";
    private static final String ACK_PARAMETER_NAME = "ack=";
    public static final String SELF_PROCESS_HEADER = "X-Self";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp";
    public static final String TIMESTAMP_STRING_HEADER = "X-Timestamp:";
    public static final String NIO_TIMESTAMP_STRING_HEADER = "x-timestamp:";

    public final String id;
    public final int from;
    public final int ack;

    public RequestData(Request request, int clusterSize) throws IllegalArgumentException {
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

        if (from <= 0 || ack <= 0 || from < ack || clusterSize < from) {
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
