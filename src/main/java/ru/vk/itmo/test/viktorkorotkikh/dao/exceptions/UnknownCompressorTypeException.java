package ru.vk.itmo.test.viktorkorotkikh.dao.exceptions;

public class UnknownCompressorTypeException extends RuntimeException {
    public UnknownCompressorTypeException(byte compressorType) {
        super("Unknown compressor type - " + compressorType);
    }
}
