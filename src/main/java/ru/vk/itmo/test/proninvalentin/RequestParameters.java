package ru.vk.itmo.test.proninvalentin;

public final class RequestParameters {
    private String key;
    private int from;
    private int ack;

    private boolean isValid = true;

    public RequestParameters(String keyString, String fromString, String ackString, int clusterSize) {
        if (Utils.isNullOrBlank(keyString)) {
            isValid = false;
            return;
        }

        key = keyString;
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
