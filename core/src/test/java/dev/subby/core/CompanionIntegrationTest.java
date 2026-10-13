package dev.subby.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CompanionIntegrationTest {
    @TempDir Path directory;
    @Test void pairingStoresOnlyInstanceCredentialsAndAcknowledgesAfterDurableSave() throws Exception {
        try (MockCompanion mock = new MockCompanion()) {
            Ledger ledger = new Ledger(directory.resolve("world")); List<String> replies = new CopyOnWriteArrayList<>();
            try (CompanionClient client = new CompanionClient(directory, () -> new ConnectionSettings("companion", mock.origin(), "Test world", false), Set::of, event -> {
                try { ledger.append(event); } catch (IOException e) { throw new UncheckedIOException(e); }
            }, message -> {})) {
                client.connect(replies::add);
                assertTrue(mock.acknowledged.await(5, TimeUnit.SECONDS));
                assertEquals(1, ledger.size()); assertEquals(1, new Ledger(directory.resolve("world")).size());
                assertEquals(2, mock.acks.get()); assertTrue(replies.stream().anyMatch(s -> s.contains("ABCDE-FGHIJ")));
                String credentials = Files.readString(directory.resolve("companion-credentials.json"));
                assertTrue(credentials.contains(mock.token)); assertFalse(credentials.contains("access_token"));
            }
        }
    }
    @Test void failedStorageIsNeverAcknowledged() throws Exception {
        try (MockCompanion mock = new MockCompanion()) {
            CountDownLatch failed = new CountDownLatch(1);
            try (CompanionClient client = new CompanionClient(directory, () -> new ConnectionSettings("companion", mock.origin(), "Test world", false), Set::of,
                    event -> { throw new UncheckedIOException(new IOException("Storage unavailable")); }, message -> failed.countDown())) {
                client.connect(message -> {}); assertTrue(failed.await(5, TimeUnit.SECONDS)); assertEquals(0, mock.acks.get());
            }
        }
    }
    @Test void existingLinkedAccountsReceiveAFreshBrowserLinkWhenPermissionApprovalIsNeeded() throws Exception {
        try (MockCompanion mock = new MockCompanion()) {
            List<String> replies = new CopyOnWriteArrayList<>();
            try (CompanionClient client = new CompanionClient(directory, () -> new ConnectionSettings("companion", mock.origin(), "Test world", false), Set::of, event -> {}, message -> {})) {
                client.connect(replies::add); assertTrue(mock.acknowledged.await(5, TimeUnit.SECONDS));
                mock.requireApproval(); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!client.status().contains("Approve optional") && System.nanoTime() < end) Thread.sleep(10);
                client.connect(replies::add); end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (mock.pairings.get() < 2 && System.nanoTime() < end) Thread.sleep(10);
                assertEquals(2, mock.pairings.get());
                while (replies.stream().filter(s -> s.startsWith("Pairing code")).count() < 2 && System.nanoTime() < end) Thread.sleep(10);
                assertEquals(2, replies.stream().filter(s -> s.startsWith("Pairing code")).count());
            }
        }
    }
    private static final class MockCompanion implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService worker = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final String id = UUID.randomUUID().toString(), token = "s".repeat(43);
        final AtomicInteger acks = new AtomicInteger(); final CountDownLatch acknowledged = new CountDownLatch(2);
        final AtomicInteger pairings = new AtomicInteger();
        private volatile Socket active;
        MockCompanion() throws IOException {
            worker.submit(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); sockets.add(socket); worker.submit(() -> handle(socket)); } catch (IOException ignored) {} });
        }
        String origin() { return "http://127.0.0.1:" + server.getLocalPort(); }
        private void handle(Socket socket) {
            try {
                InputStream in = socket.getInputStream(); ByteArrayOutputStream header = new ByteArrayOutputStream();
                int b; String text;
                do { b = in.read(); if (b < 0) return; header.write(b); text = header.toString(StandardCharsets.US_ASCII); } while (!text.endsWith("\r\n\r\n"));
                Map<String, String> headers = new HashMap<>();
                for (String line : text.split("\r\n")) if (line.contains(":")) headers.put(line.substring(0, line.indexOf(':')).toLowerCase(Locale.ROOT), line.substring(line.indexOf(':') + 1).trim());
                if (text.startsWith("POST /v1/pair ")) {
                    pairings.incrementAndGet();
                    JsonObject request = JsonParser.parseString(new String(in.readNBytes(Integer.parseInt(headers.get("content-length"))), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (request.getAsJsonArray("events").size() != 3) throw new IOException("Unexpected event scopes");
                    String body = JsonFiles.GSON.toJson(Map.of("instanceId", id, "token", token, "code", "ABCDE-FGHIJ", "link", origin() + "/link?code=ABCDE-FGHIJ", "expiresAt", System.currentTimeMillis() + 600000));
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(bytes); socket.close();
                } else if (text.startsWith("GET /v1/events?instanceId=" + id)) {
                    active = socket;
                    if (!headers.getOrDefault("authorization", "").equals("Bearer " + token)) throw new IOException("Wrong installation token");
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((headers.get("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    frame(socket, "{\"type\":\"welcome\",\"protocol\":1}");
                    frame(socket, "{\"type\":\"status\",\"linked\":true,\"channel\":\"Creator\",\"status\":\"Listening\",\"buffered\":2}");
                    Subscriber event = new Subscriber("duplicate", "123", "456", "Viewer", "sub", "1000", 1, "2026-10-13T12:00:00Z");
                    for (int seq = 1; seq <= 2; seq++) frame(socket, JsonFiles.GSON.toJson(Map.of("type", "event", "seq", seq, "event", event)));
                    DataInputStream frames = new DataInputStream(in);
                    while (!socket.isClosed()) {
                        int opcode = frames.readUnsignedByte() & 15, length = frames.readUnsignedByte(); boolean masked = (length & 128) != 0; length &= 127;
                        if (length == 126) length = frames.readUnsignedShort();
                        byte[] mask = masked ? frames.readNBytes(4) : new byte[4], value = frames.readNBytes(length);
                        for (int n = 0; n < value.length; n++) value[n] ^= mask[n % 4];
                        if (opcode == 1) { JsonObject ack = JsonParser.parseString(new String(value, StandardCharsets.UTF_8)).getAsJsonObject(); if (ack.get("type").getAsString().equals("ack")) { acks.incrementAndGet(); acknowledged.countDown(); } }
                        if (opcode == 8) return;
                    }
                }
            } catch (Exception ignored) {}
        }
        private void frame(Socket socket, String value) throws IOException {
            byte[] data = value.getBytes(StandardCharsets.UTF_8); DataOutputStream out = new DataOutputStream(socket.getOutputStream()); out.writeByte(0x81);
            if (data.length < 126) out.writeByte(data.length); else { out.writeByte(126); out.writeShort(data.length); }
            out.write(data); out.flush();
        }
        void requireApproval() throws IOException { frame(active, "{\"type\":\"status\",\"linked\":true,\"approvalRequired\":true,\"channel\":\"Creator\",\"status\":\"Approve optional permissions\",\"buffered\":0}"); }
        @Override public void close() throws IOException { server.close(); for (Socket socket : sockets) socket.close(); worker.shutdownNow(); }
    }
}
