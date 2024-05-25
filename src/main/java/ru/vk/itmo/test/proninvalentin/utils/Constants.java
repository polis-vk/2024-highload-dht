package ru.vk.itmo.test.proninvalentin.utils;

public final class Constants {
    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";
    public static final String METHOD_ADDRESS = "/v0/entity";
    public static final String ID_PARAMETER_NAME = "id=";
    public static final String REQUEST_PATH = METHOD_ADDRESS + "?" + ID_PARAMETER_NAME;
    public static final String RANGE_REQUEST_PATH = "/v0/entities?start=";
    public static final int SERVER_ERRORS = 500;
    public static final String FROM_PARAMETER_NAME = "from=";
    public static final String ACK_PARAMETER_NAME = "ack=";
    public static final String START_REQUEST_PARAMETER_NAME = "start=";
    public static final String END_REQUEST_PARAMETER_NAME = "end=";
    public static final String NIO_TIMESTAMP_HEADER = "x-timestamp:";
    public static final String HTTP_TIMESTAMP_HEADER = "X-Timestamp";
    public static final String TERMINATION_HEADER = "X-Termination";
    public static final String TERMINATION_TRUE = "true";

    private Constants() {
    }
}
