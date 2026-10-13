package dev.subby.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookStyleTest {
    private Subscriber entry(String id, String name, String kind) { return new Subscriber(id, "123", id, name, kind, "2000", 12, "2026-10-13T12:00:00Z", Map.of("login", name.toLowerCase(Locale.ROOT))); }
    @Test void extrasDoNotEnterTheBookAndPrivacyChangesApplyToLiveCopies() {
        Options options = new Options(); List<Subscriber> events = List.of(entry("a", "Alice", "sub"), entry("b", "Bob", "gift"), entry("c", "Chatter", "bits"));
        options.moderation.removedUsers.add("a"); options.book.clickableNames = true; options.book.colour = "dark_green";
        Book book = Book.create("library", events, 0, options);
        assertEquals(2, book.entries()); assertTrue(book.pages().getFirst().contains("[Removed]")); assertFalse(book.pages().getFirst().contains("Alice"));
        assertTrue(book.richPages().getFirst().stream().anyMatch(s -> s.url().equals("https://www.twitch.tv/bob")));
        options.moderation.replaceRemoved = false; assertEquals(1, Book.create("library", events, 0, options).entries());
        options.book.includeGifts = false; assertEquals(0, Book.create("library", events, 0, options).entries());
    }
    @Test void longTemplatesWrapIntoBoundedPagesAndVolumes() {
        Options options = new Options(); options.book.line = "{user} " + "wide text ".repeat(40); options.book.opening = "Welcome"; options.book.footer = "Thank you";
        List<Subscriber> entries = new ArrayList<>(); for (int i = 0; i < 200; i++) entries.add(entry("id" + i, "Viewer" + i, "sub"));
        Book latest = Book.create("library", entries, 0, options);
        assertTrue(latest.volume() > 1); assertTrue(latest.pages().size() <= 100);
        for (int volume = 1; volume <= latest.volume(); volume++) for (String page : Book.create("library", entries, volume, options).pages()) assertTrue(page.lines().count() <= 14, page);
    }
    @Test void separateSupporterWallBindingsShowDifferentGroupsOfPeople() {
        SupporterRegistry registry = new SupporterRegistry(); List<Subscriber> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) { Subscriber event = entry("id" + i, "Viewer" + i, "sub"); entries.add(event); registry.record(event); }
        Binding binding = new Binding("wall", "sign", "world", 0, 0, 0, "wall");
        assertEquals("Viewer0\nViewer1\nViewer2\nViewer3", binding.board(entries, registry.all(), 0));
        assertEquals("Viewer4\nViewer5\nViewer6\nViewer7", binding.board(entries, registry.all(), 1));
    }
}
