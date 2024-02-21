package ru.vk.itmo.test.kovalchukvladislav.dao.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import ru.vk.itmo.dao.Entry;

public interface InMemoryStorage<D, E extends Entry<D>> {
    E get(D key);

    /**
     * Вставляет значение и возвращает размер стораджа, по которому можно судить нужно ли планировать flush.
     * Бросает MemoryOverflowException если достигнут порог по размеру, а предыдущий flush() еще выполняется или упал.
     */
    long upsertAndGetSize(E entry);

    List<Iterator<E>> getIterators(D from, D to);

    /**
     * Возвращает таску для flush(). Таска возвращает новый timestamp файлов в base path.
     * Если уже выполняется flush, возвращает null.
     * Если предыдущий flush() был завершен с ошибкой, возвратит таску на повторную попытку.
     * Если нет данных, возвращает null.
     */
    Callable<String> prepareFlush(Path basePath, String dbFilenamePrefix, String offsetsFilenamePrefix);

    /**
     * Помечает flush() завершенным с ошибкой. Позволяет повторить попытку при повторных flush().
     */
    void failFlush();

    /**
     * Завершает flush() и удаляет выгружаемое дао, которое хранится пока SSTableStorage не подхватит данные с диска.
     */
    void completeFlush();

    /**
     * Синхронно и однопоточно делает flush().Возвращает новый timestamp или null.
     */
    String close(Path basePath, String dbFilenamePrefix, String offsetsFilenamePrefix) throws IOException;
}
