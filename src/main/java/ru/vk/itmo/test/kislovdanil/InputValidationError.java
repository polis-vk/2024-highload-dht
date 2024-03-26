package ru.vk.itmo.test.kislovdanil;

public class InputValidationError extends RuntimeException {
    public InputValidationError(String s, Exception e) {
        super(s, e);
    }
}
