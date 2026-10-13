package dev.subby.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LibraryTest {
    @TempDir Path directory;
    private Subscriber entry(int i) { return new Subscriber("event-" + i, "123", "user-" + i, "Subscriber" + i, "sub", "1000", 1, "2026-10-13T12:00:00Z"); }

    @Test void duplicatesRemainSuppressedAfterRestartAndVolumesRollOver() throws Exception {
        Ledger ledger = new Ledger(directory);
        for (int i = 0; i < 401; i++) assertTrue(ledger.append(entry(i)));
        assertFalse(ledger.append(entry(400)));
        assertEquals(2, ledger.book(0).volume());
        assertEquals(100, ledger.book(1).pages().size());
        assertEquals(1, ledger.book(2).pages().size());
        Ledger restarted = new Ledger(directory);
        assertEquals(401, restarted.size());
        assertEquals(ledger.book(0), restarted.book(0));
        assertFalse(restarted.append(entry(0)));
        assertThrows(IllegalArgumentException.class, () -> restarted.book(3));
    }
    @Test void damagedJournalIsPreservedAndStartupFails() throws Exception {
        Ledger ledger = new Ledger(directory);
        ledger.append(entry(0));
        Files.writeString(directory.resolve("subscribers.jsonl"), "{truncated", StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(directory.resolve("subscribers.jsonl"));
        assertThrows(java.io.IOException.class, () -> new Ledger(directory));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("subscribers.jsonl")));
    }
    @Test void channelLibrariesCannotBeAccidentallyMixed() throws Exception {
        Ledger ledger = new Ledger(directory);
        ledger.append(entry(1));
        assertThrows(java.io.IOException.class, () -> ledger.append(new Subscriber("other", "999", "u", "Someone", "sub", "1000", 1, "now")));
        assertEquals(1, ledger.size());
    }
    @Test void completeLastEventWithoutNewlineCanBeAppendedSafely() throws Exception {
        Ledger ledger = new Ledger(directory);
        ledger.append(entry(1));
        Path journal = directory.resolve("subscribers.jsonl");
        Files.writeString(journal, Files.readString(journal).stripTrailing());
        Ledger restarted = new Ledger(directory);
        restarted.append(entry(2));
        assertEquals(2, new Ledger(directory).size());
    }
    @Test void emptyBookAndLongUnicodeNamesStayInsideVanillaLimits() {
        assertEquals(1, Book.create("id", List.of(), 0).pages().size());
        List<Subscriber> entries = new ArrayList<>();
        for (int i = 0; i < 4; i++) entries.add(new Subscriber("id" + i, "123", "u", "界".repeat(25), "gift", "3000", 1, "now"));
        Book book = Book.create("id", entries, 0);
        assertTrue(book.pages().getFirst().lines().count() <= 14);
        assertTrue(book.title().length() <= 32);
    }
    @Test void subscriberNamesCannotInjectFormattingOrNewLines() {
        assertEquals("abcX", Subscriber.safeName("a\nb\rc\u00a7\u202eX"));
        assertEquals(25, Subscriber.safeName("x".repeat(100)).length());
    }
    @Test void eventParsingDistinguishesGiftRecipientsAndResubMessages() {
        JsonObject gift = event("channel.subscribe", "true", 1);
        Subscriber parsed = Subscriber.notification(gift, "123", true);
        assertEquals("gift", parsed.kind());
        assertEquals("Recipient", parsed.name());
        assertNull(Subscriber.notification(gift, "999", true));
        JsonObject resub = event("channel.subscription.message", "false", 14);
        assertEquals(14, Subscriber.notification(resub, "123", true).months());
        assertNull(Subscriber.notification(resub, "123", false));
        assertNull(Subscriber.notification(event("channel.subscription.gift", "false", 1), "123", true));
    }
    private JsonObject event(String type, String gift, int months) {
        return JsonParser.parseString("""
          {"metadata":{"message_id":"abc","message_type":"notification","subscription_type":"%s"},
           "payload":{"event":{"broadcaster_user_id":"123","user_id":"recipient","user_name":"Recipient",
           "user_login":"recipient","is_gift":%s,"tier":"1000","cumulative_months":%d}}}
          """.formatted(type, gift, months)).getAsJsonObject();
    }
    @Test void functionRulesAllowFixedDatapackIdsAndRejectViewerCommandText() {
        assertDoesNotThrow(() -> new Rules.Action("function", "subby:on_sub"));
        assertThrows(IllegalArgumentException.class, () -> new Rules.Action("function", "subby:{user}"));
        assertThrows(IllegalArgumentException.class, () -> new Rules.Action("function", "subby:ok\nstop"));
        assertThrows(IllegalArgumentException.class, () -> new Rules.Action("command", "op {user}"));
        assertEquals("Subscriber1 has 1 months", new Rules.Action("message", "{user} has {months} months").message(entry(1)));
    }
    @Test void invalidConfigurationIsNeverOverwritten() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{bad config");
        assertThrows(java.io.IOException.class, () -> JsonFiles.read(path, Config.class, Config.defaults()));
        assertEquals("{bad config", Files.readString(path));
    }
}
