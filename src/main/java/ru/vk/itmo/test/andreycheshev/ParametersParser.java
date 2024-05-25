package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Request;

public final class ParametersParser {
    private static final String ID_PARAMETER = "id=";
    private static final String ACK_PARAMETER = "ack=";
    private static final String FROM_PARAMETER = "from=";

    private ParametersParser() {

    }

    public static String parseId(Request request) {
        String id = request.getParameter(ID_PARAMETER);
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Illegal parameter");
        }
        return id;
    }

    public static ParametersTuple<Integer> parseAckFromOrDefault(
            Request request,
            RendezvousDistributor distributor) {

        String ack = request.getParameter(ACK_PARAMETER);
        String from = request.getParameter(FROM_PARAMETER);

        if (ack == null || from == null) {
            return distributor.getDefaultAckFrom();
        } else {
            return new ParametersTuple<>(
                    Integer.parseInt(ack),
                    Integer.parseInt(from)
            );
        }
    }
}
