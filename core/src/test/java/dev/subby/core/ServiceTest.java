package dev.subby.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {
    @TempDir Path directory;
    @Test void defaultSetupRecordsNamesWithoutAnyExtraEffects() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        JsonFiles.write(settings.resolve("config.json"), new Config("", false, true, false));
        FakePlatform platform = new FakePlatform();
        List<String> replies = new CopyOnWriteArrayList<>();
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            service.command(replies::add, new String[]{"test", "Someone"});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (replies.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(replies.getFirst().startsWith("Demo entry recorded"));
            service.tick();
            assertEquals(1, platform.book.entries());
            assertTrue(platform.actions.isEmpty());
        }
    }
    @Test void pausingAndSaturatingEffectsNeverSkipsSubscriberRecords() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        JsonFiles.write(settings.resolve("config.json"), new Config("", false, true, false));
        JsonFiles.write(settings.resolve("rules.json"), new Rules(List.of(new Rules.Rule("sub", 0, 0,
                List.of(new Rules.Action("message", "Hello {user}"))))));
        FakePlatform platform = new FakePlatform();
        List<String> replies = new CopyOnWriteArrayList<>();
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            service.command(replies::add, new String[]{"pause"});
            for (int i = 0; i < 300; i++) service.command(replies::add, new String[]{"test", "User" + i});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (replies.stream().filter(s -> s.startsWith("Demo entry")).count() < 300 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(300, new Ledger(world).size(), "Effect queue limits must never discard names");
            service.tick(); assertEquals(0, platform.actions.size());
            service.command(replies::add, new String[]{"status"});
            assertTrue(replies.getLast().contains("256 effects queued (paused)"));
            service.command(replies::add, new String[]{"resume"});
            service.tick(); assertEquals(4, platform.actions.size());
            for (int i = 0; i < 63; i++) service.tick();
            assertEquals(256, platform.actions.size());
            assertEquals(300, platform.book.entries());
        }
    }
    private static class FakePlatform implements Platform {
        final List<String> actions = new ArrayList<>();
        Book book;
        public void execute(Runnable action) { action.run(); }
        public void log(String message) {}
        public void give(Audience audience, Book book, boolean live) {}
        public LecternTarget targetedLectern(Audience audience) { return new LecternTarget("world", 0, 0, 0); }
        public void refresh(Book book, List<LecternTarget> lecterns) { this.book = book; }
        public void announce(String name) {}
        public void action(Rules.Action action, Subscriber event) { actions.add(action.message(event)); }
    }
    @Test void storageFailureDoesNotClaimThatADemoEntryWasRecorded() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        JsonFiles.write(settings.resolve("config.json"), new Config("", false, true, false));
        List<String> replies = new CopyOnWriteArrayList<>();
        try (SubbyService service = new SubbyService(settings, world, new FakePlatform())) {
            Files.createDirectory(world.resolve("subscribers.jsonl"));
            service.command(replies::add, new String[]{"test", "Someone"});
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (replies.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, replies.size());
            assertTrue(replies.getFirst().startsWith("Entry was not recorded"));
        }
    }
}
