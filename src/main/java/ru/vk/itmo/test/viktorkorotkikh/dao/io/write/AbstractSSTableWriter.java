package ru.vk.itmo.test.viktorkorotkikh.dao.io.write;

import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.viktorkorotkikh.dao.SSTable;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.ByteArraySegment;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;

/**
 * Base class for implementing the sstable writer.
 *
 * @author vitekkor
 * @see BaseSSTableWriter
 */
public abstract class AbstractSSTableWriter {
    private static final int BUFFER_SIZE = 64 * 1024;
    protected final ByteArraySegment longBuffer = new ByteArraySegment(Long.BYTES);
    protected final ByteArraySegment blobBuffer;

    private static final long INDEX_METADATA_SIZE = Long.BYTES + 1L; // entries size + hasNoTombstones flag

    protected AbstractSSTableWriter() {
        blobBuffer = new ByteArraySegment(512);
    }

    /**
     * Writes entries to SSTable files including data, index, and compression information.
     *
     * @param isCompacted A boolean flag indicating whether the SSTable is compacted.
     * @param entries     Iterator over the entries to be written.
     * @param baseDir     The base directory for storing SSTable files.
     * @param fileIndex   The index of the SSTable file.
     * @throws IOException if an I/O error occurs during writing.
     */
    public void write(
            boolean isCompacted,
            Iterator<? extends Entry<MemorySegment>> entries,
            final Path baseDir,
            final int fileIndex
    ) throws IOException {
        // Write to temporary files
        final Path tempIndexName = SSTable.tempIndexName(isCompacted, baseDir, fileIndex);
        final Path tempDataName = SSTable.tempDataName(isCompacted, baseDir, fileIndex);

        // Delete temporary files to eliminate tails
        Files.deleteIfExists(tempIndexName);
        Files.deleteIfExists(tempDataName);

        // Iterate in a single pass!
        // Will write through FileChannel despite extra memory copying and
        // no buffering (which may be implemented later).
        // Looking forward to MemorySegment facilities in FileChannel!
        try (
                OutputStream index =
                        new BufferedOutputStream(
                                new FileOutputStream(
                                        tempIndexName.toFile()),
                                BUFFER_SIZE);
                OutputStream data =
                        new BufferedOutputStream(
                                new FileOutputStream(
                                        tempDataName.toFile()),
                                BUFFER_SIZE)) {
            index.write(new byte[(int) INDEX_METADATA_SIZE]); // write 0, fill in the data later

            int entriesSize = 0;
            boolean hasNoTombstones = true;

            while (entries.hasNext()) {
                // Then write the entry
                final Entry<MemorySegment> entry = entries.next();
                hasNoTombstones &= entry.value() != null;
                writeEntry(entry, data, index);
                entriesSize++;
            }

            index.flush();

            // map the index and compression info files for updating metadata
            try (Arena arena = Arena.ofConfined();
                 FileChannel indexFileChannel = FileChannel.open(
                         tempIndexName,
                         StandardOpenOption.READ,
                         StandardOpenOption.WRITE
                 )
            ) {
                MemorySegment mappedIndexFile = indexFileChannel.map(
                        FileChannel.MapMode.READ_WRITE,
                        0L,
                        INDEX_METADATA_SIZE,
                        arena
                );
                // write final index information and compression info metadata.
                writeIndexInfo(mappedIndexFile, entriesSize, hasNoTombstones);
                // force changes to the files to be written to the storage device.
                mappedIndexFile.force();
            }
        }

        renameTmpFiles(isCompacted, baseDir, fileIndex, tempIndexName, tempDataName);
    }

    /**
     * Rename the temporary files to their final names, making the SSTable available for use.
     *
     * @param isCompacted   A boolean flag indicating whether the SSTable is compacted.
     * @param baseDir       The base directory for storing SSTable files.
     * @param fileIndex     The index of the SSTable file.
     * @param tempIndexName A temporary index file path.
     * @param tempDataName  A temporary data file path.
     * @throws IOException if an I/O error occurs during writing.
     */
    private static void renameTmpFiles(
            boolean isCompacted,
            Path baseDir,
            int fileIndex,
            Path tempIndexName,
            Path tempDataName
    ) throws IOException {
        // Publish files atomically
        // FIRST index, LAST data
        final Path indexName = SSTable.indexName(isCompacted, baseDir, fileIndex);
        Files.move(
                tempIndexName,
                indexName,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        final Path dataName = SSTable.dataName(isCompacted, baseDir, fileIndex);
        Files.move(
                tempDataName,
                dataName,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
    }

    /**
     * Write final index information and compression info metadata.
     *
     * @param mappedIndexFile A mapped index file.
     * @param entriesSize     Count of entries.
     * @param hasNoTombstones hasNoTombstones flag
     */
    protected abstract void writeIndexInfo(
            MemorySegment mappedIndexFile,
            int entriesSize,
            boolean hasNoTombstones
    );

    /**
     * Writes entry into os stream. Also writes additional information into compressionInfoStream and indexStream.
     *
     * @param entry       Entry to write.
     * @param os          Data output stream.
     * @param indexStream Index output stream.
     * @throws IOException if an I/O error occurs during writing.
     */
    protected abstract void writeEntry(
            final Entry<MemorySegment> entry,
            final OutputStream os,
            final OutputStream indexStream
    ) throws IOException;
}
