package dev.subby.core;

import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;


public final class TwitchClient implements AutoCloseable {
    record Credentials(String clientId, String accessToken, String refreshToken) {
        Credentials {
            if (clientId == null || accessToken == null || accessToken.isBlank() || refreshToken == null || refreshToken.isBlank())
                throw new IllegalArgumentException("Incomplete credentials.json; sign in again after restoring/removing the damaged file.");
        }
    }
    record Endpoints(String identity, String helix, String websocket) {
        static Endpoints twitch() { return new Endpoints("https://id.twitch.tv/oauth2/", "https://api.twitch.tv/helix/", "wss://eventsub.wss.twitch.tv/ws"); }
    }
    private static final String SCOPE = "channel:read:subscriptions";
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Subby-Twitch"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Path credentialFile;
    private final Supplier<Config> config;
    private final Consumer<Subscriber> subscriber;
    private final Consumer<String> log;
    private final Endpoints endpoints;
    private Credentials credentials;
    private String broadcasterId = "";
    private boolean wanted;
    private ScheduledFuture<?> retryTask;
    private int retrySeconds = 2;
    private long epoch, loginEpoch, nextValidation;
    private Session active, pending;
    private volatile String status = "Disconnected";

    public TwitchClient(Path directory, Supplier<Config> config, Consumer<Subscriber> subscriber, Consumer<String> log) throws IOException {
        this(directory, config, subscriber, log, Endpoints.twitch());
    }
    TwitchClient(Path directory, Supplier<Config> config, Consumer<Subscriber> subscriber, Consumer<String> log, Endpoints endpoints) throws IOException {
        credentialFile = directory.resolve("credentials.json");
        this.config = config; this.subscriber = subscriber; this.log = log; this.endpoints = endpoints;
        credentials = JsonFiles.read(credentialFile, Credentials.class, null);
        worker.scheduleWithFixedDelay(this::maintenance, 5, 5, TimeUnit.SECONDS);
    }
    public String status() { return status; }
    public void connect() { worker.execute(() -> { if (!wanted) { wanted = true; retrySeconds = 2; start(); } }); }
    public void disconnect() { worker.execute(() -> { loginEpoch++; stop("Disconnected"); }); }
    public void logout() { worker.execute(() -> {
        loginEpoch++; stop("Logged out"); credentials = null;
        try { Files.deleteIfExists(credentialFile); } catch (IOException e) { log.accept("Could not remove credentials.json; remove it manually."); }
    }); }

    public void login(Consumer<String> reply) {
        worker.execute(() -> {
            if (config.get().clientId().isBlank()) { reply.accept("Run /subby setup <Twitch public application client ID> first."); return; }
            stop("Waiting for Twitch login");
            long attempt = ++loginEpoch;
            String clientId = config.get().clientId();
            try {
                JsonObject code = postForm("device", Map.of("client_id", clientId, "scopes", SCOPE));
                String url = code.get("verification_uri").getAsString();
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) || !"www.twitch.tv".equals(uri.getHost())) throw new IOException("Unexpected login URL");
                reply.accept("Sign in as the broadcaster: " + url + "  Code: " + code.get("user_code").getAsString());
                int interval = Math.max(5, code.get("interval").getAsInt());
                pollLogin(attempt, clientId, code.get("device_code").getAsString(),
                        System.currentTimeMillis() + code.get("expires_in").getAsLong() * 1000, interval, reply);
            } catch (Exception e) { status = "Login could not start"; reply.accept("Twitch login could not start. Check your public client ID and internet connection."); }
        });
    }

    private void pollLogin(long attempt, String clientId, String code, long deadline, int interval, Consumer<String> reply) {
        worker.schedule(() -> {
            if (attempt != loginEpoch) return;
            if (System.currentTimeMillis() > deadline) { status = "Login expired"; reply.accept("Login expired. Run /subby login again."); return; }
            try {
                JsonObject token = postForm("token", Map.of("client_id", clientId, "scopes", SCOPE, "device_code", code,
                        "grant_type", "urn:ietf:params:oauth:grant-type:device_code"));
                credentials = tokens(clientId, token);
                JsonFiles.write(credentialFile, credentials);
                wanted = true; start();
                reply.accept("Twitch login saved. " + status);
            } catch (ApiException e) {
                if (e.reason.equals("authorization_pending") || e.reason.equals("slow_down"))
                    pollLogin(attempt, clientId, code, deadline, e.reason.equals("slow_down") ? interval + 5 : interval, reply);
                else if (e.code == 429 || e.code >= 500) pollLogin(attempt, clientId, code, deadline, interval + 5, reply);
                else { status = "Login declined or expired"; reply.accept("Login declined or expired. Run /subby login again."); }
            } catch (IOException e) { pollLogin(attempt, clientId, code, deadline, interval, reply); }
            catch (Exception e) { status = "Login failed"; reply.accept("Login failed. Run /subby login again."); }
        }, interval, TimeUnit.SECONDS);
    }

    private Credentials tokens(String clientId, JsonObject token) {
        return new Credentials(clientId, token.get("access_token").getAsString(), token.get("refresh_token").getAsString());
    }
    private void refreshToken() throws IOException, InterruptedException {
        if (credentials == null || credentials.refreshToken == null || credentials.refreshToken.isBlank()) throw new ApiException(401, "");
        JsonObject token = postForm("token", Map.of("client_id", credentials.clientId, "refresh_token", credentials.refreshToken, "grant_type", "refresh_token"));
        credentials = tokens(credentials.clientId, token);

        JsonFiles.write(credentialFile, credentials);
    }
    private void validate() throws IOException, InterruptedException {
        if (credentials == null || !config.get().clientId().equals(credentials.clientId)) throw new ApiException(401, "");
        JsonObject validation;
        try { validation = request(HttpRequest.newBuilder(URI.create(endpoints.identity + "validate"))
                .header("Authorization", "OAuth " + credentials.accessToken).GET()); }
        catch (ApiException e) {
            if (e.code != 401) throw e;
            refreshToken();
            validation = request(HttpRequest.newBuilder(URI.create(endpoints.identity + "validate"))
                    .header("Authorization", "OAuth " + credentials.accessToken).GET());
        }
        if (!credentials.clientId.equals(validation.get("client_id").getAsString()) ||
            !validation.has("user_id") || !validation.getAsJsonArray("scopes").contains(new JsonPrimitive(SCOPE)))
            throw new ApiException(403, "");
        if (validation.get("expires_in").getAsInt() < 300) refreshToken();
        broadcasterId = validation.get("user_id").getAsString();
        nextValidation = System.currentTimeMillis() + Duration.ofMinutes(55).toMillis();
    }

    private void start() {
        if (!wanted) return;
        if (credentials == null || !config.get().clientId().equals(credentials.clientId)) { stop("Run /subby login to connect Twitch"); return; }
        try {
            status = "Connecting to Twitch";
            validate();
            open(URI.create(endpoints.websocket), false);
        } catch (Exception e) { failure(e); }
    }
    private void open(URI uri, boolean transfer) {
        if (pending != null) pending.abort();
        Session session = new Session(epoch, transfer);
        pending = session;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, session).whenComplete((socket, error) -> {
            if (error != null) enqueue(() -> { if (session.current()) failure(error); });
        });
    }
    private void subscribe(String type, String sessionId) throws IOException, InterruptedException {
        JsonObject body = new JsonObject(); body.addProperty("type", type); body.addProperty("version", "1");
        JsonObject condition = new JsonObject(); condition.addProperty("broadcaster_user_id", broadcasterId); body.add("condition", condition);
        JsonObject transport = new JsonObject(); transport.addProperty("method", "websocket"); transport.addProperty("session_id", sessionId); body.add("transport", transport);
        request(HttpRequest.newBuilder(URI.create(endpoints.helix + "eventsub/subscriptions"))
                .header("Client-Id", credentials.clientId).header("Authorization", "Bearer " + credentials.accessToken)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())));
    }
    private void receive(Session session, String text) {
        if (!session.current()) return;
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            String type = root.getAsJsonObject("metadata").get("message_type").getAsString();
            JsonObject payload = root.getAsJsonObject("payload");
            switch (type) {
                case "session_welcome" -> {
                    JsonObject info = payload.getAsJsonObject("session");
                    session.timeout = info.get("keepalive_timeout_seconds").getAsInt() * 1000L;
                    if (!session.transfer) {
                        subscribe("channel.subscribe", info.get("id").getAsString());
                        if (config.get().includeResubscriptions()) subscribe("channel.subscription.message", info.get("id").getAsString());
                    }
                    Session old = active;
                    active = session; pending = null;
                    if (old != null && old != session) old.abort();
                    retrySeconds = 2; status = "Connected to Twitch (channel " + broadcasterId + ")";
                    log.accept(status);
                }
                case "notification" -> {
                    Subscriber event = Subscriber.notification(root, broadcasterId, config.get().includeResubscriptions());
                    if (event != null) subscriber.accept(event);
                }
                case "session_reconnect" -> {
                    URI uri = URI.create(payload.getAsJsonObject("session").get("reconnect_url").getAsString());
                    if (!"wss".equals(uri.getScheme()) || !"eventsub.wss.twitch.tv".equals(uri.getHost()) || uri.getUserInfo() != null)
                        throw new IOException("Unexpected reconnect URL");
                    open(uri, true);
                }
                case "revocation" -> stop("Twitch authorization revoked. Run /subby login again.");
                default -> {   }
            }
        } catch (Exception e) { failure(e); }
    }

    private void maintenance() {
        if (!wanted) return;
        long now = System.currentTimeMillis();
        if ((pending != null && now - pending.lastMessage > 20000) ||
            (active != null && now - active.lastMessage > active.timeout + 5000)) { failure(new IOException()); return; }
        if (active != null && now >= nextValidation) {
            try { validate(); } catch (Exception e) { failure(e); }
        }
    }
    private void failure(Throwable error) {
        closeSockets(); epoch++;
        if (!wanted) return;
        if (error instanceof java.io.UncheckedIOException) {
            stop("Recording stopped because subscriber storage failed. Check the server log before reconnecting."); return;
        }
        if (error instanceof ApiException api && (api.code == 400 || api.code == 401 || api.code == 403)) {
            stop("Twitch authorization failed. Check the public client ID and run /subby login again."); return;
        }
        status = "Twitch disconnected; retrying in " + retrySeconds + " seconds";
        log.accept(status);
        if (retryTask != null) retryTask.cancel(false);
        long attempt = epoch;
        retryTask = worker.schedule(() -> {
            retryTask = null;
            if (wanted && attempt == epoch) start();
        }, retrySeconds, TimeUnit.SECONDS);
        retrySeconds = Math.min(120, retrySeconds * 2);
    }
    private void stop(String reason) {
        wanted = false; epoch++;
        if (retryTask != null) { retryTask.cancel(false); retryTask = null; }
        closeSockets(); status = reason; log.accept(reason);
    }
    private void closeSockets() {
        if (active != null) active.abort();
        if (pending != null) pending.abort();
        active = null; pending = null;
    }
    private void enqueue(Runnable action) { try { worker.execute(action); } catch (RejectedExecutionException ignored) {} }

    private JsonObject postForm(String path, Map<String, String> data) throws IOException, InterruptedException {
        String body = data.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8) + "=" +
                URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        return request(HttpRequest.newBuilder(URI.create(endpoints.identity + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)));
    }
    private JsonObject request(HttpRequest.Builder builder) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(builder.timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofString());
        JsonObject body;
        try { body = JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("Invalid Twitch response"); }
        if (response.statusCode() / 100 != 2) {
            String reason = body.has("message") ? body.get("message").getAsString() : body.has("error") ? body.get("error").getAsString() : "";
            throw new ApiException(response.statusCode(), reason);
        }
        return body;
    }
    private static final class ApiException extends IOException {
        final int code; final String reason;
        ApiException(int code, String reason) { super("Twitch HTTP " + code); this.code = code; this.reason = reason; }
    }
    private final class Session implements WebSocket.Listener {
        final long generation; final boolean transfer;
        final StringBuilder fragments = new StringBuilder();
        volatile WebSocket socket;
        volatile long lastMessage = System.currentTimeMillis();
        long timeout = 30000;
        Session(long generation, boolean transfer) { this.generation = generation; this.transfer = transfer; }
        boolean current() { return wanted && generation == epoch && (this == pending || this == active); }
        void abort() { if (socket != null) socket.abort(); }
        @Override public void onOpen(WebSocket socket) {
            this.socket = socket; socket.request(1);
            enqueue(() -> { if (!current()) abort(); });
        }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            lastMessage = System.currentTimeMillis();
            if (fragments.length() + data.length() > 1_048_576) { socket.abort(); enqueue(() -> { if (current()) failure(new IOException()); }); return null; }
            fragments.append(data);
            if (last) { String text = fragments.toString(); fragments.setLength(0); enqueue(() -> receive(this, text)); }
            socket.request(1); return null;
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { enqueue(() -> { if (current()) failure(new IOException()); }); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { enqueue(() -> { if (current()) failure(error); }); }
    }
    @Override public void close() {
        try { worker.submit(() -> { loginEpoch++; stop("Stopped"); }).get(3, TimeUnit.SECONDS); }
        catch (Exception ignored) { closeSockets(); }
        worker.shutdownNow(); http.shutdownNow();
    }
}
