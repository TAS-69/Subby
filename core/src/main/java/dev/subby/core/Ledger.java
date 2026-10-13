package dev.subby.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;


public final class Ledger {
    private record Header(String libraryId, String broadcasterId) {}
    private final Path directory;
    private final List<Subscriber> entries = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private Header header;
    private Book latest;

    public Ledger(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        Path headerPath = directory.resolve("library.json");
        header = JsonFiles.read(headerPath, Header.class, new Header(UUID.randomUUID().toString(), ""));
        if (header.libraryId == null || header.broadcasterId == null) throw new IOException("Invalid library.json");
        if (!Files.exists(headerPath)) JsonFiles.write(headerPath, header);
        Path journal = directory.resolve("subscribers.jsonl");
        if (Files.exists(journal)) {
            int lineNumber = 0;
            try (var lines = Files.newBufferedReader(journal, StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) continue;
                    try {
                        Subscriber entry = JsonFiles.GSON.fromJson(line, Subscriber.class);
                        if (entry == null) throw new IllegalArgumentException();
                        if (seen.add(entry.messageId())) entries.add(entry);
                    } catch (RuntimeException e) {
                        throw new IOException("Damaged subscriber journal at line " + lineNumber + ". Restore a backup; the original is preserved.", e);
                    }
                }
            }


            try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                if (channel.size() > 0) {
                    ByteBuffer last = ByteBuffer.allocate(1);
                    channel.read(last, channel.size() - 1);
                    if (last.array()[0] != '\n') {
                        channel.position(channel.size()); channel.write(ByteBuffer.wrap(new byte[]{'\n'})); channel.force(true);
                    }
                }
            }
        }
        latest = Book.create(header.libraryId, entries, 0);
    }

    public synchronized boolean append(Subscriber entry) throws IOException {
        if (seen.contains(entry.messageId())) return false;
        if (!entry.broadcasterId().isEmpty()) {
            if (!header.broadcasterId.isEmpty() && !header.broadcasterId.equals(entry.broadcasterId()))
                throw new IOException("This library belongs to another Twitch channel. Use another world or restore its account.");
            if (header.broadcasterId.isEmpty()) {
                header = new Header(header.libraryId, entry.broadcasterId());
                JsonFiles.write(directory.resolve("library.json"), header);
            }
        }
        byte[] bytes = (JsonFiles.GSON.toJson(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(directory.resolve("subscribers.jsonl"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        seen.add(entry.messageId());
        entries.add(entry);
        latest = Book.create(header.libraryId, entries, 0);
        return true;
    }

    public synchronized Book book(int volume) { return volume == 0 ? latest : Book.create(header.libraryId, entries, volume); }
    public synchronized int size() { return entries.size(); }
    public synchronized String broadcasterId() { return header.broadcasterId; }
    public synchronized String supporter(String name) {
        List<Subscriber> matches = entries.stream().filter(e -> e.name().equalsIgnoreCase(name)).toList();
        if (matches.isEmpty()) return "No supporter named " + name + " has been recorded.";
        Subscriber latest = matches.getLast();
        return latest.name() + " | " + matches.size() + " events | first seen " + matches.getFirst().receivedAt() +
                " | " + matches.stream().mapToInt(Subscriber::months).max().orElse(1) + " months | tier " + latest.tier() +
                " | gifted subscriptions received " + matches.stream().filter(e -> e.kind().equals("gift")).count();
    }
}
