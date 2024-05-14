package ru.vk.itmo.test.viktorkorotkikh.dao.io.write;

import ru.vk.itmo.test.viktorkorotkikh.dao.TimestampedEntry;
import ru.vk.itmo.test.viktorkorotkikh.dao.io.ByteArraySegment;
import ru.vk.itmo.test.viktorkorotkikh.dao.sstable.SSTableUtils;

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
 * @see CompressedSSTableWriter
 */
public abstract class AbstractSSTableWriter {
    private static final int BUFFER_SIZE = 64 * 1024;
    protected final ByteArraySegment longBuffer = new ByteArraySegment(Long.BYTES);
    protected final ByteArraySegment blobBuffer;
    protected final int blockSize;

    private static final long INDEX_METADATA_SIZE = Long.BYTES + 1L; // entries size + hasNoTombstones flag

    private static final int COMPRESSION_INFO_METADATA_SIZE = 2 * Integer.BYTES; // blocksCount|blockSize

    protected AbstractSSTableWriter(int blockSize) {
        this.blockSize = blockSize;
        if (this.blockSize <= 0) { // set default blob buffer
            blobBuffer = new ByteArraySegment(512);
        } else {
            blobBuffer = new ByteArraySegment(blockSize, false);
        }
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
            Iterator<? extends TimestampedEntry<MemorySegment>> entries,
            final Path baseDir,
            final int fileIndex
    ) throws IOException {
        // Write to temporary files
        final Path tempCompressionInfo = SSTableUtils.tempCompressionInfoName(isCompacted, baseDir, fileIndex);
        final Path tempIndexName = SSTableUtils.tempIndexName(isCompacted, baseDir, fileIndex);
        final Path tempDataName = SSTableUtils.tempDataName(isCompacted, baseDir, fileIndex);

        // Delete temporary files to eliminate tails
        Files.deleteIfExists(tempIndexName);
        Files.deleteIfExists(tempDataName);

        // Iterate in a single pass!
        // Will write through FileChannel despite extra memory copying and
        // no buffering (which may be implemented later).
        // Looking forward to MemorySegment facilities in FileChannel!
        try (OutputStream compressionInfo =
                     new BufferedOutputStream(
                             new FileOutputStream(
                                     tempCompressionInfo.toFile()),
                             BUFFER_SIZE);
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

            writeCompressionHeader(compressionInfo);
            compressionInfo.write(new byte[COMPRESSION_INFO_METADATA_SIZE]); // write 0s, fill in the data later

            int entriesSize = 0;
            boolean hasNoTombstones = true;

            while (entries.hasNext()) {
                // Then write the entry
                final TimestampedEntry<MemorySegment> entry = entries.next();
                hasNoTombstones &= entry.value() != null;
                writeEntry(entry, data, compressionInfo, index);
                entriesSize++;
            }

            index.flush();
            compressionInfo.flush();
            finish(data, compressionInfo);

            // map the index and compression info files for updating metadata
            try (Arena arena = Arena.ofConfined();
                 FileChannel indexFileChannel = FileChannel.open(
                         tempIndexName,
                         StandardOpenOption.READ,
                         StandardOpenOption.WRITE
                 );
                 FileChannel compressionInfoFileChannel = FileChannel.open(
                         tempCompressionInfo,
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

                MemorySegment mappedCompressionInfoFile = compressionInfoFileChannel.map(
                        FileChannel.MapMode.READ_WRITE,
                        2L, // isCompressed|algorithm
                        COMPRESSION_INFO_METADATA_SIZE,
                        arena
                );
                // write final index information and compression info metadata.
                writeIndexInfo(mappedIndexFile, mappedCompressionInfoFile, entriesSize, hasNoTombstones);
                // force changes to the files to be written to the storage device.
                mappedIndexFile.force();
                mappedCompressionInfoFile.force();
            }
        }

        renameTmpFiles(isCompacted, baseDir, fileIndex, tempCompressionInfo, tempIndexName, tempDataName);
    }

    /**
     * Rename the temporary files to their final names, making the SSTable available for use.
     *
     * @param isCompacted         A boolean flag indicating whether the SSTable is compacted.
     * @param baseDir             The base directory for storing SSTable files.
     * @param fileIndex           The index of the SSTable file.
     * @param tempCompressionInfo A temporary compression info file path.
     * @param tempIndexName       A temporary index file path.
     * @param tempDataName        A temporary data file path.
     * @throws IOException if an I/O error occurs during writing.
     */
    private static void renameTmpFiles(
            boolean isCompacted,
            Path baseDir,
            int fileIndex,
            Path tempCompressionInfo,
            Path tempIndexName,
            Path tempDataName
    ) throws IOException {
        // Publish files atomically
        // FIRST index, LAST data
        final Path compressionInfoName = SSTableUtils.compressionInfoName(isCompacted, baseDir, fileIndex);
        Files.move(
                tempCompressionInfo,
                compressionInfoName,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        final Path indexName = SSTableUtils.indexName(isCompacted, baseDir, fileIndex);
        Files.move(
                tempIndexName,
                indexName,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
        );
        final Path dataName = SSTableUtils.dataName(isCompacted, baseDir, fileIndex);
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
     * @param mappedIndexFile           A mapped index file.
     * @param mappedCompressionInfoFile A mapped compression info file.
     * @param entriesSize               Count of entries.
     * @param hasNoTombstones           hasNoTombstones flag
     */
    protected abstract void writeIndexInfo(
            MemorySegment mappedIndexFile,
            MemorySegment mappedCompressionInfoFile,
            int entriesSize,
            boolean hasNoTombstones
    );

    /**
     * Writes entry into os stream. Also writes additional information into compressionInfoStream and indexStream.
     *
     * @param entry                 Entry to write.
     * @param os                    Data output stream.
     * @param compressionInfoStream Compression info output stream.
     * @param indexStream           Index output stream.
     * @throws IOException if an I/O error occurs during writing.
     */
    protected abstract void writeEntry(
            final TimestampedEntry<MemorySegment> entry,
            final OutputStream os,
            final OutputStream compressionInfoStream,
            final OutputStream indexStream
    ) throws IOException;

    /**
     * Flush blobBuffer to outputStream forcibly.
     *
     * @param os                    outputStream for writing
     * @param compressionInfoStream compression info outputStream
     * @throws IOException if an I/O error occurs.
     */
    protected abstract void finish(OutputStream os, OutputStream compressionInfoStream) throws IOException;

    /**
     * Write the header for compression information.
     *
     * @param os Compression info output stream.
     * @throws IOException if an I/O error occurs.
     */
    protected abstract void writeCompressionHeader(final OutputStream os) throws IOException;
}
