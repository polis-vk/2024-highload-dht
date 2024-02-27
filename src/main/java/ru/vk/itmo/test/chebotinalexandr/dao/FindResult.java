package ru.vk.itmo.test.chebotinalexandr.dao;

/** Binary search in SSTable result information.
 */
public record FindResult(boolean found, long index) { }
