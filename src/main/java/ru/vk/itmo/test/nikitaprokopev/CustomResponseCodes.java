package ru.vk.itmo.test.nikitaprokopev;

public enum CustomResponseCodes {
    TOO_MANY_REQUESTS("429 Too Many Requests"),
    RESPONSE_NOT_ENOUGH_REPLICAS("504 Not Enough Replicas");

    private final String code;

    CustomResponseCodes(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
