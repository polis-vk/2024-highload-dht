package ru.vk.itmo.test.abramovilya.dao.table;

public interface Table {
    TableEntry currentEntry();

    TableEntry nextEntry();
}
