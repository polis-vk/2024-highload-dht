package ru.vk.itmo.test.ryabovvadim.dao.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileUtils {
    public static final String DATA_FILE_EXT = "data";
    public static final String TMP_FILE_EXT = "tmp";
    public static final String DELETED_FILE_EXT = "del";
    private static final String FILE_EXTENSION_DELIMITER = ".";

    public static Path makePath(Path prefix, String name, String extension) {
        return Path.of(prefix.toString(), name + "." + extension);
    }
    
    public static void createParentDirectories(Path path) throws IOException {
        if (path == null || path.getParent() == null) {
            return;
        }
        
        Path parent = path.getParent();
        if (Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
    }

    public static boolean hasExtension(Path path, String extension) {
        return path.getFileName().toString().endsWith(FILE_EXTENSION_DELIMITER + extension);
    }

    public static String extractFileName(Path path, String extension) {
        String fullFileName = path.getFileName().toString();
        int index = fullFileName.indexOf(FILE_EXTENSION_DELIMITER + extension);

        if (index == -1) {
            throw new IllegalArgumentException("File " + path + " doesn't have extension " + extension);
        }

        return fullFileName.substring(0, index);
    }

    private FileUtils() {
    }
}
