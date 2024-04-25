package ru.vk.itmo.test.smirnovandrew;

import one.nio.http.Request;
import one.nio.http.Response;
import ru.vk.itmo.dao.BaseEntry;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Iterator;

public class MyServerDao {
    private final Dao<MemorySegment, Entry<MemorySegment>> dao;

    public MyServerDao(Dao<MemorySegment, Entry<MemorySegment>> dao) {
        this.dao = dao;
    }

    Iterator<Entry<MemorySegment>> getEntriesFromDao(String start, String end) {
        return dao.get(DaoFactory.fromString(start), DaoFactory.fromString(end));
    }

    Response getEntryFromDao(String id) {
        Entry<MemorySegment> entry = dao.get(DaoFactory.fromString(id));
        if (entry == null) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        }
        try {
            ValWithTime valueWithTimestamp = byteArrayToObject(entry.value().toArray(ValueLayout.JAVA_BYTE));
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
        ValWithTime valueWithTimestamp = new ValWithTime(request.getBody(), System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id),
                    MemorySegment.ofArray(objToByteArray(valueWithTimestamp))));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    Response deleteValueFromDao(String id) {
        ValWithTime valueWithTimestamp = new ValWithTime(null, System.currentTimeMillis());
        try {
            dao.upsert(new BaseEntry<>(DaoFactory.fromString(id),
                    MemorySegment.ofArray(objToByteArray(valueWithTimestamp))));
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
        return new Response(Response.ACCEPTED, Response.EMPTY);
    }

    static ValWithTime byteArrayToObject(byte[] bytes) throws IOException, ClassNotFoundException {
        var byteArrayInputStream = new ByteArrayInputStream(bytes);
        try (var objectInputStream = new ObjectInputStream(byteArrayInputStream)) {
            return (ValWithTime) objectInputStream.readObject();
        }
    }

    private static byte[] objToByteArray(ValWithTime object) throws IOException {
        var byteArrayOutputStream = new ByteArrayOutputStream();
        try (var objectOutputStream = new ObjectOutputStream(byteArrayOutputStream)) {
            objectOutputStream.writeObject(object);
            return byteArrayOutputStream.toByteArray();
        }
    }
}