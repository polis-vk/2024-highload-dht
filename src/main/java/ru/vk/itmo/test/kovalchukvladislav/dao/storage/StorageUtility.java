package ru.vk.itmo.test.kovalchukvladislav.dao.storage;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.stream.Stream;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.EntryExtractor;
import ru.vk.itmo.test.kovalchukvladislav.dao.model.TableInfo;

public final class StorageUtility {
    private static final OpenOption[] WRITE_OPTIONS = new OpenOption[] {
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.CREATE
    };
    private static final int OFFSET_SIZE = Long.BYTES;

    private StorageUtility() {
    }

    // Удаление ненужных файлов не является чем то критически важным
    // Если произойдет исключение, лучше словить и вывести в лог, чем останавливать работу
    public static void deleteUnusedFiles(Logger logger, Path... files) {
        for (Path file : files) {
            try {
                boolean deleted = Files.deleteIfExists(file);
                if (deleted) {
                    logger.info(() -> String.format("File %s was deleted", file));
                } else {
                    logger.severe(() -> String.format("File %s not deleted", file));
                }
            } catch (IOException e) {
                logger.severe(() -> String.format("Error while deleting file %s: %s", file, e.getMessage()));
            }
        }
    }

    public static void deleteUnusedFilesInDirectory(Logger logger, Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            Path[] array = files.sorted(Comparator.reverseOrder()).toArray(Path[]::new);
            deleteUnusedFiles(logger, array);
        } catch (Exception e) {
            logger.severe(() -> String.format("Error while deleting directory %s: %s", directory, e.getMessage()));
        }
    }

    public static <D, E extends Entry<D>> void writeData(Path dbPath, Path offsetsPath,
                                                         Iterator<E> daoIterator, TableInfo info,
                                                         EntryExtractor<D, E> extractor) throws IOException {

        try (FileChannel db = FileChannel.open(dbPath, WRITE_OPTIONS);
             FileChannel offsets = FileChannel.open(offsetsPath, WRITE_OPTIONS);
             Arena arena = Arena.ofConfined()) {

            long offsetsSize = info.recordsCount() * OFFSET_SIZE;
            MemorySegment fileSegment = db.map(FileChannel.MapMode.READ_WRITE, 0, info.recordsSize(), arena);
            MemorySegment offsetsSegment = offsets.map(FileChannel.MapMode.READ_WRITE, 0, offsetsSize, arena);

            int i = 0;
            long offset = 0;
            while (daoIterator.hasNext()) {
                E entry = daoIterator.next();
                offsetsSegment.setAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, i, offset);
                offset = extractor.writeEntry(entry, fileSegment, offset);
                i += 1;
            }

            fileSegment.load();
            offsetsSegment.load();
        }
    }
}
