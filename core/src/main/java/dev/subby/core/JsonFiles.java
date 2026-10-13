package dev.subby.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class JsonFiles {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private JsonFiles() {}

    public static <T> T read(Path path, Class<T> type, T fallback) throws IOException {
        if (!Files.exists(path)) return fallback;
        try {
            T result = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
            if (result == null) throw new IllegalArgumentException("Empty JSON");
            return result;
        } catch (RuntimeException e) {
            throw new IOException("Invalid JSON in " + path.getFileName() + "; restore a backup before starting Subby.", e);
        }
    }

    public static void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".subby-", ".tmp");
        try {
            Files.writeString(temp, new GsonBuilder().setPrettyPrinting().create().toJson(value), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
