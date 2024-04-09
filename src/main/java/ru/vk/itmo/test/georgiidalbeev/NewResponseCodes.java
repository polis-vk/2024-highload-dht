package ru.vk.itmo.test.georgiidalbeev;

public enum NewResponseCodes {
    TOO_MANY_REQUESTS("429 Too Many Requests");

    private final String code;

    NewResponseCodes(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

