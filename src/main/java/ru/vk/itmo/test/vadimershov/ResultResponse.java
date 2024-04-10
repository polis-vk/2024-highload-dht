package ru.vk.itmo.test.vadimershov;

public record ResultResponse(long httpCode, byte[] value, Long timestamp) { }
