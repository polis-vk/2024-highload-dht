package ru.vk.itmo.test.proninvalentin.request_parameter;

import ru.vk.itmo.test.proninvalentin.utils.Utils;

public final class RequestParameters {
    private final String key;
    private int from;
    private int ack;
    private boolean isValid = true;

    public RequestParameters(String keyString, String fromString, String ackString, int clusterSize) {
        key = keyString;
        if (Utils.isNullOrBlank(keyString)) {
            isValid = false;
            return;
        }

        try {
            from = Utils.isNullOrBlank(fromString)
                    ? clusterSize
                    : Integer.parseInt(fromString);
            ack = Utils.isNullOrBlank(ackString)
                    ? (from + 1) / 2
                    : Integer.parseInt(ackString);
        } catch (NumberFormatException ex) {
            isValid = false;
        }

        if (from <= 0 || clusterSize < from || from < ack || ack <= 0) {
            isValid = false;
        }
    }

    public boolean isValid() {
        return isValid;
    }

    public String key() {
        return key;
    }

    public int from() {
        return from;
    }

    public int ack() {
        return ack;
    }
}
