package ru.vk.itmo.test.volkovnikita.exception;

public class NotEnoughReplicasException extends RuntimeException {
    public NotEnoughReplicasException(String message) {
        super(message);
    }
}
