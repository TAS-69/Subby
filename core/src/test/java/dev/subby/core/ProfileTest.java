package dev.subby.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProfileTest {
    @TempDir Path directory;
    private static final class User implements Platform.Audience {
        final String id = UUID.randomUUID().toString(); final List<String> messages = new ArrayList<>(); boolean admin;
        User(boolean admin) { this.admin = admin; }
        public void message(String message) { messages.add(message); }
        @Override public String id() { return id; }
        @Override public boolean permitted(String permission) { return admin; }
    }
    @Test void independentProfilesKeepSignInsAndLibrariesSeparateAndRecheckDelegatedPermissions() throws Exception {
        User admin = new User(true), reader = new User(false); Path world = directory.resolve("world");
        try (SubbyManager manager = new SubbyManager(directory.resolve("settings"), world, new ServiceTest.FakePlatform())) {
            manager.command(admin, new String[]{"profile", "create", "creator_two"});
            assertTrue(Files.exists(world.resolve("profiles/creator_two/library/library.json")));
            assertNotEquals(new Ledger(world).libraryId(), new Ledger(world.resolve("profiles/creator_two/library")).libraryId());
            assertFalse(manager.allowed(reader));
            manager.command(admin, new String[]{"profile", "grant", "creator_two", reader.id, "book"}); assertTrue(manager.allowed(reader));
            manager.command(reader, new String[]{"profile", "switch", "creator_two"});
            manager.command(reader, new String[]{"connect"}); assertTrue(reader.messages.getLast().contains("do not have permission"));
            manager.command(reader, new String[]{"pause"}); assertTrue(reader.messages.getLast().contains("do not have permission"));
            manager.command(admin, new String[]{"profile", "revoke", "creator_two", reader.id}); assertFalse(manager.allowed(reader));
        }
        try (SubbyManager reopened = new SubbyManager(directory.resolve("settings"), world, new ServiceTest.FakePlatform())) { assertFalse(reopened.allowed(reader)); }
    }
    @Test void legacyDirectSettingsMigratePerWorldWithoutMovingOrDeletingTheOriginal() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        JsonFiles.write(settings.resolve("config.json"), new Config("legacy", false, true, false));
        try (SubbyManager ignored = new SubbyManager(settings, world, new ServiceTest.FakePlatform())) {
            Path migrated = world.resolve("profiles/default/settings");
            assertEquals("legacy", JsonFiles.read(migrated.resolve("config.json"), Config.class, null).clientId());
            assertEquals("direct", JsonFiles.read(migrated.resolve("connection.json"), ConnectionSettings.class, null).mode());
            assertTrue(Files.exists(settings.resolve("config.json")));
        }
    }
}
