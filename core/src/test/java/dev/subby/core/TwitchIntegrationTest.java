package dev.subby.core;

import com.sun.net.httpserver.HttpServer;
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

class TwitchIntegrationTest {
    @TempDir Path directory;

    @Test void expiredTokenRotatesAndRealWebSocketNotificationsAreDeduplicated() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        AtomicInteger subscriptions = new AtomicInteger();
        List<String> logs = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        try (MockTwitch mock = new MockTwitch(refreshes, subscriptions)) {
            JsonFiles.write(directory.resolve("credentials.json"), new TwitchClient.Credentials("client", "old-secret", "old-refresh-secret"));
            Ledger ledger = new Ledger(directory.resolve("world"));
            try (TwitchClient client = new TwitchClient(directory, () -> new Config("client", true, true, false), event -> {
                try { ledger.append(event); received.countDown(); } catch (IOException e) { throw new UncheckedIOException(e); }
            }, logs::add, mock.endpoints())) {
                client.connect();
                assertTrue(received.await(10, TimeUnit.SECONDS), "Expected duplicate delivery through a real WebSocket");
                assertEquals(1, refreshes.get());
                assertEquals(2, subscriptions.get());
                assertEquals(1, ledger.size());
                assertTrue(client.status().startsWith("Connected"));
                assertEquals("Recipient", ledger.book(0).pages().getFirst().lines().skip(2).findFirst().orElseThrow());
                assertEquals("new-refresh-secret", JsonFiles.read(directory.resolve("credentials.json"), TwitchClient.Credentials.class, null).refreshToken());
                assertTrue(logs.stream().noneMatch(line -> line.contains("secret")), "Tokens must never reach logs");
                mock.revoke();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!client.status().contains("revoked") && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(client.status().contains("revoked"));
            }
        }
    }

    private static final class MockTwitch implements AutoCloseable {
        private final HttpServer http;
        private final ServerSocket ws;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        private final AtomicInteger subscriptions;
        private final CountDownLatch subscribed = new CountDownLatch(2);
        private volatile Socket socket;
        MockTwitch(AtomicInteger refreshes, AtomicInteger subscriptions) throws Exception {
            this.subscriptions = subscriptions;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(executor);
            http.createContext("/oauth2/validate", exchange -> {
                boolean old = exchange.getRequestHeaders().getFirst("Authorization").contains("old-secret");
                byte[] body = (old ? "{\"message\":\"invalid token\"}" : "{\"client_id\":\"client\",\"user_id\":\"123\",\"scopes\":[\"channel:read:subscriptions\"],\"expires_in\":3600}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(old ? 401 : 200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            http.createContext("/oauth2/token", exchange -> {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!form.contains("old-refresh-secret")) throw new AssertionError("Wrong refresh token");
                refreshes.incrementAndGet();
                byte[] body = "{\"access_token\":\"new-secret\",\"refresh_token\":\"new-refresh-secret\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            http.createContext("/helix/eventsub/subscriptions", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!body.contains("test-session") || !body.contains("123") || !"Bearer new-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) throw new AssertionError("Wrong EventSub registration");
                subscriptions.incrementAndGet(); subscribed.countDown();
                byte[] response = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(202, response.length); exchange.getResponseBody().write(response); exchange.close();
            });
            http.start();
            ws = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                try {
                    socket = ws.accept();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String line, key = null;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) if (line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")) key = line.substring(line.indexOf(':') + 1).trim();
                    String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    frame("{\"metadata\":{\"message_type\":\"session_welcome\"},\"payload\":{\"session\":{\"id\":\"test-session\",\"keepalive_timeout_seconds\":30}}}");
                    if (!subscribed.await(5, TimeUnit.SECONDS)) throw new AssertionError("Subscriptions missing");
                    String event = "{\"metadata\":{\"message_id\":\"duplicate-id\",\"message_type\":\"notification\",\"subscription_type\":\"channel.subscribe\"},\"payload\":{\"event\":{\"broadcaster_user_id\":\"123\",\"user_id\":\"recipient\",\"user_name\":\"Recipient\",\"user_login\":\"recipient\",\"is_gift\":true,\"tier\":\"1000\"}}}";
                    frame(event); frame(event);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        TwitchClient.Endpoints endpoints() {
            String base = "http://127.0.0.1:" + http.getAddress().getPort();
            return new TwitchClient.Endpoints(base + "/oauth2/", base + "/helix/", "ws://127.0.0.1:" + ws.getLocalPort() + "/ws");
        }
        synchronized void frame(String value) throws IOException {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeByte(0x81);
            if (data.length < 126) out.writeByte(data.length); else { out.writeByte(126); out.writeShort(data.length); }
            out.write(data); out.flush();
        }
        void revoke() throws IOException { frame("{\"metadata\":{\"message_type\":\"revocation\"},\"payload\":{}}"); }
        @Override public void close() throws Exception { if (socket != null) socket.close(); ws.close(); http.stop(0); executor.shutdownNow(); }
    }
}
