package dev.subby.core;

import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServiceTest {
    @TempDir Path directory;
    private Subscriber entry(int id) { return new Subscriber("event-" + id, "123", "u" + id, "Someone" + id, "sub", "1000", 1, "2026-10-13T12:00:00Z"); }
    @Test void defaultSetupRecordsNamesWithoutAnyExtraEffectsAndTestsArePrivatePreviews() throws Exception {
        FakePlatform platform = new FakePlatform();
        try (SubbyService service = new SubbyService(directory.resolve("settings"), directory.resolve("world"), platform)) {
            service.acceptEvent(entry(1)); service.tick();
            assertEquals(1, platform.book.entries()); assertTrue(platform.actions.isEmpty());
            service.command(message -> {}, new String[]{"test", "Sample"});
            assertEquals(1, new Ledger(directory.resolve("world")).size());
            assertEquals(1, platform.preview.entries()); assertTrue(platform.preview.pages().getFirst().contains("Sample"));
        }
    }
    @Test void pausingAndSaturatingEffectsNeverSkipsNamesAndPendingEffectsSurviveRestart() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        Options options = new Options(); options.advancedEnabled = true;
        JsonFiles.write(settings.resolve("options.json"), options);
        JsonFiles.write(settings.resolve("rules.json"), new Rules(List.of(new Rules.Rule("sub", 0, 0, List.of(new Rules.Action("message", "Hello {user}"))))));
        FakePlatform platform = new FakePlatform(); List<String> replies = new ArrayList<>();
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            service.command(replies::add, new String[]{"pause"});
            for (int i = 0; i < 300; i++) service.acceptEvent(entry(i));
            assertEquals(300, new Ledger(world).size()); service.tick(); assertTrue(platform.actions.isEmpty());
            service.command(replies::add, new String[]{"status"}); assertTrue(replies.getLast().contains("256 effects queued (paused)"));
        }
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            assertTrue(service.paused()); service.command(replies::add, new String[]{"resume"}); service.tick();
            assertEquals(4, platform.actions.size()); assertEquals(300, platform.book.entries());
            service.acceptEvent(entry(299)); service.command(replies::add, new String[]{"status"});
            assertTrue(replies.getLast().contains("252 effects queued"));
        }
    }
    @Test void storageFailureIsReportedBeforeTheDeliveryCanBeAcknowledged() throws Exception {
        Path world = directory.resolve("world");
        try (SubbyService service = new SubbyService(directory.resolve("settings"), world, new FakePlatform())) {
            Files.createDirectory(world.resolve("subscribers.jsonl"));
            assertThrows(UncheckedIOException.class, () -> service.acceptEvent(entry(1)));
            assertEquals(0, service.currentBook().entries());
        }
    }
    @Test void configuredNameFilteringKeepsRawHistoryPrivateUntilTheFilterCompletes() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        Options options = new Options(); options.moderation.minecraftFilter = true; options.targetPlayer = "Creator"; JsonFiles.write(settings.resolve("options.json"), options);
        java.util.concurrent.CompletableFuture<String> answer = new java.util.concurrent.CompletableFuture<>();
        FakePlatform platform = new FakePlatform() {
            @Override public boolean nameFilterAvailable(String name) { return true; }
            @Override public java.util.concurrent.CompletableFuture<String> filterName(String name, String player) { return answer; }
        };
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            service.acceptEvent(entry(1)); service.tick(); assertFalse(platform.book.pages().getFirst().contains("Someone1"));
            assertTrue(platform.book.pages().getFirst().contains("[Awaiting filter]"));
            answer.complete(""); long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(world.resolve("name-filters.json")) && System.nanoTime() < end) Thread.sleep(10);
            service.tick(); assertTrue(platform.book.pages().getFirst().contains("[Filtered]"));
            assertEquals("Someone1", new Ledger(world).entries().getFirst().name());
        }
    }
    @Test void runningLocalTestEffectsNeverAddsFakeNamesOrPersistsTestsAcrossRestart() throws Exception {
        Path settings = directory.resolve("settings"), world = directory.resolve("world");
        Options options = new Options(); options.advancedEnabled = true; JsonFiles.write(settings.resolve("options.json"), options);
        JsonFiles.write(settings.resolve("rules.json"), new Rules(List.of(new Rules.Rule("bits", 0, 0, List.of(new Rules.Action("message", "Thanks for {bits} Bits, {user}!"))))));
        FakePlatform platform = new FakePlatform();
        try (SubbyService service = new SubbyService(settings, world, platform)) {
            service.runTest(message -> {}, SubbyService.sample("bits", "Example", 500, "", 1, 1)); service.tick();
            assertEquals(List.of("Thanks for 500 Bits, Example!"), platform.actions); assertEquals(0, new Ledger(world).size());
            service.runTest(message -> {}, SubbyService.sample("bits", "PendingExample", 500, "", 1, 1));
        }
        try (SubbyService reopened = new SubbyService(settings, world, platform)) { reopened.tick(); assertEquals(1, platform.actions.size()); assertEquals(0, reopened.currentBook().entries()); }
    }
    static class FakePlatform implements Platform {
        final List<String> actions = new ArrayList<>(); Book book, preview;
        public void execute(Runnable action) { action.run(); }
        public void log(String message) {}
        public void give(Audience audience, Book book, boolean live) { preview = book; }
        public LecternTarget targetedLectern(Audience audience) { return new LecternTarget("world", 0, 0, 0); }
        public void refresh(Book book, List<LecternTarget> lecterns) { this.book = book; }
        public void announce(String name) {}
        public void action(Rules.Action action, Subscriber event) { actions.add(action.message(event)); }
    }
}
