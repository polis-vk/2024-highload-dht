package ru.vk.itmo.test.volkovnikita.util;

public class NotEnoughReplicasException extends RuntimeException {
    public NotEnoughReplicasException(String message) {
        super(message);
    }
}
