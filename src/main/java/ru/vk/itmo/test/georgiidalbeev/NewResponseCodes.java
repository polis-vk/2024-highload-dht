package ru.vk.itmo.test.georgiidalbeev;

public enum NewResponseCodes {
    TOO_MANY_REQUESTS("429 Too Many Requests"),
    NOT_ENOUGH_REPLICAS("504 Not Enough Replicas");

    private final String code;

    NewResponseCodes(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

