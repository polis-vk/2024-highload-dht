package ru.vk.itmo.test.nikitaprokopev;

public enum CustomResponseCodes {
    TOO_MANY_REQUESTS("429 Too Many Requests");

    private final String code;

    CustomResponseCodes(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
