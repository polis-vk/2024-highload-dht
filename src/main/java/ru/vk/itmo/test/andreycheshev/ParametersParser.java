package ru.vk.itmo.test.andreycheshev;

import one.nio.http.Request;

public class ParametersParser {
    private static final String ACK_PARAMETER = "ack=";
    private static final String FROM_PARAMETER = "from=";
    private static final String START_PARAMETER = "start=";
    private static final String END_PARAMETER = "end=";

    private ParametersParser() {

    }

    public static ParametersTuple<Integer> parseAckFrom(
            Request request,
            RendezvousDistributor distributor) throws IllegalArgumentException {

        String ack = request.getParameter(ACK_PARAMETER);
        String from = request.getParameter(FROM_PARAMETER);

        if (ack == null || from == null) {
            return getDefaultAckFrom(distributor);
        } else {
            try {
                ParametersTuple<Integer> params = new ParametersTuple<>(
                        Integer.parseInt(ack),
                        Integer.parseInt(from)
                );

                if (params.isValidAckFrom()) {
                    throw new IllegalArgumentException();
                }

                return params;
            } catch (NumberFormatException e) {
                return getDefaultAckFrom(distributor);
            }
        }
    }

    private static ParametersTuple<Integer> getDefaultAckFrom(RendezvousDistributor distributor) {
        return new ParametersTuple<>(
                distributor.getQuorumNumber(),
                distributor.getNodeCount()
        );
    }

    public static ParametersTuple<String> parseStartEnd(
            Request request) throws IllegalArgumentException {

        String start = request.getParameter(START_PARAMETER);
        String end = request.getParameter(END_PARAMETER);

        if (start == null) {
            throw new IllegalArgumentException();
        }

        // end may be null.
        return new ParametersTuple<>(start, end);
    }
}
