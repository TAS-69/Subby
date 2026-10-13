package dev.subby.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class CompanionClient implements AutoCloseable {
    private static final class RemovedInstallation extends IOException {}
    private record Credentials(String instanceId, String token, String origin, Set<String> events, String code, String link, long expiresAt) {}
    private final Path credentialsFile;
    private final Supplier<ConnectionSettings> settings;
    private final Supplier<Set<String>> events;
    private final Consumer<Subscriber> received;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Subby-Companion"); thread.setDaemon(true); return thread;
    });
    private Credentials credentials;
    private WebSocket socket;
    private ScheduledFuture<?> retry, watchdog;
    private volatile String status = "Companion disconnected";
    private boolean stopped = true, closed, linked, approvalRequired;
    private int generation, attempts;
    private Runnable afterLink = () -> {};
    private Supplier<String> expectedChannel = () -> "";
    public void onLinked(Runnable action) { afterLink = Objects.requireNonNull(action); }
    public void expectedChannel(Supplier<String> channel) { expectedChannel = Objects.requireNonNull(channel); }

    public CompanionClient(Path directory, Supplier<ConnectionSettings> settings, Supplier<Set<String>> events,
                           Consumer<Subscriber> received, Consumer<String> log) throws IOException {
        credentialsFile = directory.resolve("companion-credentials.json");
        credentials = JsonFiles.read(credentialsFile, Credentials.class, null);
        if (credentials != null && (credentials.instanceId == null || credentials.token == null || !credentials.token.matches("[A-Za-z0-9_-]{43}") || credentials.origin == null || credentials.events == null))
            throw new IOException("Invalid Companion credentials; the original file was preserved.");
        this.settings = settings; this.events = events; this.received = received; this.log = log;
    }
    public String status() { return status; }
    public void connect(Platform.Audience audience) {
        worker.execute(() -> {
            if (closed) return;
            stopped = false;
            try {
                ConnectionSettings config = settings.get();
                if (credentials != null && !credentials.origin.equals(config.serviceUrl())) throw new IllegalArgumentException("Remove the saved Companion sign-in before changing service addresses.");
                Set<String> requested = new TreeSet<>(events.get()); requested.addAll(Set.of("sub", "resub", "gift"));
                boolean paired = credentials == null || !requested.equals(credentials.events) || linked && approvalRequired || !linked && credentials.expiresAt < System.currentTimeMillis();
                if (paired) pair(config, requested);
                if (audience != null && (paired || !linked || approvalRequired) && credentials.link != null) {
                    audience.message("Pairing code: " + credentials.code + " (expires in ten minutes).");
                    audience.link("Connect your Twitch account", credentials.link);
                    audience.message("Check the code in your browser, sign in, then confirm your channel.");
                }
                open();
            } catch (Exception e) {
                status = "Companion unavailable";
                if (audience != null) audience.message(e instanceof IllegalArgumentException ? e.getMessage() : "Could not reach Companion. Check the service address in Advanced > Connection.");
                scheduleRetry();
            }
        });
    }
    public void resume() { worker.execute(() -> { if (credentials != null && !closed) { stopped = false; open(); } }); }
    private void pair(ConnectionSettings config, Set<String> requested) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.add("events", JsonFiles.GSON.toJsonTree(requested)); body.addProperty("label", config.label());
        body.addProperty("expectedChannel", expectedChannel.get());
        if (credentials != null) body.addProperty("instanceId", credentials.instanceId);
        JsonObject result;
        try { result = post("/v1/pair", body); }
        catch (RemovedInstallation e) { Files.deleteIfExists(credentialsFile); credentials = null; body.remove("instanceId"); result = post("/v1/pair", body); }
        String token = credentials == null ? result.get("token").getAsString() : credentials.token;
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Unexpected installation credential");
        String id = result.get("instanceId").getAsString(); UUID.fromString(id);
        String link = result.get("link").getAsString();
        if (!URI.create(link).resolve("/").equals(URI.create(config.serviceUrl() + "/"))) throw new IOException("Unexpected pairing address");
        Credentials replacement = new Credentials(id, token, config.serviceUrl(), Set.copyOf(requested), result.get("code").getAsString(), link, result.get("expiresAt").getAsLong());
        JsonFiles.write(credentialsFile, replacement); credentials = replacement;
    }
    private JsonObject post(String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.get().serviceUrl() + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonFiles.GSON.toJson(body)));
        if (credentials != null) request.header("Authorization", "Bearer " + credentials.token);
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 503 && path.equals("/v1/pair")) throw new IllegalArgumentException("Companion is not ready for sign-in. Ask its operator to finish Twitch setup.");
        if (response.statusCode() == 410) { if (path.equals("/v1/unlink")) return new JsonObject(); throw new RemovedInstallation(); }
        if (response.statusCode() != 200 || response.body().length() > 16384) throw new IOException("Companion request failed: " + response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private void open() {
        if (closed || stopped || credentials == null) return;
        if (retry != null) retry.cancel(false);
        if (socket != null) socket.abort();
        int attempt = ++generation;
        status = "Connecting to Companion";
        String address = credentials.origin.replaceFirst("^http", "ws") + "/v1/events?instanceId=" + credentials.instanceId;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + credentials.token)
                .buildAsync(URI.create(address), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();
                    @Override public void onOpen(WebSocket ws) {
                        worker.execute(() -> {
                            if (attempt != generation || stopped || closed) { ws.abort(); return; }
                            socket = ws; watch(attempt); ws.request(1);
                        });
                    }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
                        if (buffer.length() + text.length() > 65536) { ws.abort(); return CompletableFuture.completedFuture(null); }
                        buffer.append(text);
                        if (!last) { ws.request(1); return CompletableFuture.completedFuture(null); }
                        String message = buffer.toString(); buffer.setLength(0);
                        worker.execute(() -> {
                            if (attempt != generation || stopped || closed) return;
                            try { receive(ws, message); watch(attempt); ws.request(1); }
                            catch (Exception e) { log.accept("Companion delivery was not acknowledged. It will be retried; check storage and channel settings."); failed(attempt); }
                        });
                        return CompletableFuture.completedFuture(null);
                    }
                    @Override public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) { ws.request(1); return ws.sendPong(message); }
                    @Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { worker.execute(() -> failed(attempt)); return CompletableFuture.completedFuture(null); }
                    @Override public void onError(WebSocket ws, Throwable error) { worker.execute(() -> failed(attempt)); }
                }).exceptionally(error -> {
                    worker.execute(() -> {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        if (attempt == generation && cause instanceof WebSocketHandshakeException handshake && handshake.getResponse().statusCode() == 410) { stopped = true; status = "Pairing expired. Use /subby connect for a fresh code."; }
                        else failed(attempt);
                    }); return null;
                });
    }
    private void receive(WebSocket ws, String text) {
        JsonObject message = JsonParser.parseString(text).getAsJsonObject();
        switch (message.get("type").getAsString()) {
            case "welcome" -> { if (message.get("protocol").getAsInt() != 1) throw new IllegalArgumentException("Unsupported Companion protocol"); attempts = 0; }
            case "status" -> {
                boolean wasLinked = linked;
                boolean wasApprovalRequired = approvalRequired;
                linked = message.get("linked").getAsBoolean();
                approvalRequired = message.has("approvalRequired") && message.get("approvalRequired").getAsBoolean();
                status = linked ? "Twitch: " + Subscriber.safeName(message.get("channel").getAsString()) + " | " + message.get("status").getAsString().replaceAll("[\\p{Cntrl}§]", "").substring(0, Math.min(160, message.get("status").getAsString().replaceAll("[\\p{Cntrl}§]", "").length())) : "Waiting for browser sign-in";
                if (linked && !approvalRequired && (!wasLinked || wasApprovalRequired)) afterLink.run();
            }
            case "event" -> {
                long seq = message.get("seq").getAsLong(); if (seq < 1) throw new IllegalArgumentException("Invalid sequence");
                Subscriber event = JsonFiles.GSON.fromJson(message.get("event"), Subscriber.class);
                received.accept(event);
                ws.sendText("{\"type\":\"ack\",\"seq\":" + seq + "}", true).join();
            }
            default -> throw new IllegalArgumentException("Unsupported Companion message");
        }
    }
    private void watch(int attempt) {
        if (watchdog != null) watchdog.cancel(false);
        watchdog = worker.schedule(() -> failed(attempt), 75, TimeUnit.SECONDS);
    }
    private void failed(int attempt) {
        if (attempt != generation || stopped || closed) return;
        generation++;
        if (socket != null) { socket.abort(); socket = null; }
        status = "Reconnecting to Companion"; scheduleRetry();
    }
    private void scheduleRetry() {
        if (stopped || closed || credentials == null) return;
        if (retry != null) retry.cancel(false);
        retry = worker.schedule(this::open, Math.min(60, 1L << Math.min(attempts++, 6)), TimeUnit.SECONDS);
    }
    public void disconnect() {
        worker.execute(() -> {
            stopped = true; generation++;
            if (retry != null) retry.cancel(false); if (watchdog != null) watchdog.cancel(false);
            if (socket != null) { socket.abort(); socket = null; }
            status = "Companion disconnected";
        });
    }
    public void logout(Platform.Audience audience) {
        worker.execute(() -> {
            try {
                if (credentials != null) { JsonObject body = new JsonObject(); body.addProperty("instanceId", credentials.instanceId); post("/v1/unlink", body); }
                Files.deleteIfExists(credentialsFile); credentials = null; linked = false; disconnect();
                audience.message("Companion sign-in removed. Saved Minecraft names are kept.");
            } catch (IOException | InterruptedException e) { audience.message("Could not unlink at Companion. Your local credentials are kept so you can retry."); }
        });
    }
    public void recover(Platform.Audience audience) {
        worker.execute(() -> {
            if (credentials == null) { audience.message("Connect to Companion first."); return; }
            try {
                JsonObject body = new JsonObject(); body.addProperty("instanceId", credentials.instanceId); post("/v1/recover", body);
                audience.message("Recovering current active subscribers. Check connection status for progress. Original subscription dates and months cannot be recovered.");
            } catch (IOException | InterruptedException e) { audience.message("Could not start recovery. Check that Companion is linked and reachable."); }
        });
    }
    public void bot(boolean enabled, String style, String template, Platform.Audience audience) {
        worker.execute(() -> {
            if (credentials == null) { audience.message("Connect to Companion first."); return; }
            try {
                JsonObject body = new JsonObject(); body.addProperty("instanceId", credentials.instanceId); body.addProperty("enabled", enabled); body.addProperty("style", style); body.addProperty("template", template);
                post("/v1/bot", body); audience.message("Companion chat settings saved.");
            } catch (IOException | InterruptedException e) { audience.message("Could not update chat settings. Reconnect to approve the bot permission first."); }
        });
    }
    @Override public void close() {
        if (closed) return;
        try { worker.submit(() -> { closed = true; stopped = true; generation++; if (socket != null) socket.abort(); }).get(5, TimeUnit.SECONDS); }
        catch (Exception e) { if (socket != null) socket.abort(); }
        worker.shutdownNow(); http.close();
    }
}
