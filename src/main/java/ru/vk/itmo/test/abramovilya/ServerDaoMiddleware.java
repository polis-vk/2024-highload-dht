package ru.vk.itmo.test.abramovilya;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;
import ru.vk.itmo.test.abramovilya.util.Util;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class ServerDaoMiddleware {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServerDaoMiddleware(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        this.dao = dao;
    }

    Response getEntryFromDao(String id) {
        Entry<MemorySegment> entry = dao.get(DaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        try {
            ValueWithTimestamp valueWithTimestamp =
                    Util.byteArrayToObject(entry.value().toArray(ValueLayout.JAVA_BYTE));
            if (valueWithTimestamp.value() == null) {
                Response response = new Response(Response.NOT_FOUND, Response.EMPTY);
                response.addHeader("X-Timestamp: " + valueWithTimestamp.timestamp());
                return response;
            }
            Response response = new Response(Response.OK, valueWithTimestamp.value());
            response.addHeader("X-Timestamp: " + valueWithTimestamp.timestamp());
            return response;
        } catch (IOException | ClassNotFoundException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    Response putEntryIntoDao(String id, Request request) {
        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(request.getBody(), System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<MemorySegment>(DaoFactory.fromString(id),
                    MemorySegment.ofArray(Util.objToByteArray(valueWithTimestamp))));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    Response deleteValueFromDao(String id) {
        ValueWithTimestamp valueWithTimestamp = new ValueWithTimestamp(null, System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<MemorySegment>(DaoFactory.fromString(id),
                    MemorySegment.ofArray(Util.objToByteArray(valueWithTimestamp))));
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }
}