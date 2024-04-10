package ru.vk.itmo.test.timofeevkirill.reference;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class ExceptionUtils {
    private static final Logger log = LoggerFactory.getLogger(ExceptionUtils.class);

    public static void handleErrorFromHandleRequest(Exception e, HttpSession session) {
        log.error("Exception during handleRequest", e);
        try {
            session.sendResponse(new Response(Response.INTERNAL_ERROR, Response.EMPTY));
        } catch (IOException ex) {
            log.error("Exception while sending close connection", ex);
            session.scheduleClose();
        }
    }

    private ExceptionUtils() {

    }
}
