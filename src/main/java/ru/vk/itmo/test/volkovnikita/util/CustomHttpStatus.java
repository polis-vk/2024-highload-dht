package ru.vk.itmo.test.volkovnikita.util;

public enum CustomHttpStatus {

    TOO_MANY_REQUESTS(429, "Too Many Requests");

    private final int value;
    private final String reasonPhrase;

    CustomHttpStatus(int value, String reasonPhrase) {
        this.value = value;
        this.reasonPhrase = reasonPhrase;
    }

    @Override
    public String toString() {
        return this.value + this.reasonPhrase;
    }
}
