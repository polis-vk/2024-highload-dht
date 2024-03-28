package ru.vk.itmo.test.kislovdanil.service;

public class InvalidTopologyError extends RuntimeException {
    public InvalidTopologyError() {
        super("Invalid network topology was set. Check your service configuration.");
    }
}
