package dev.subby.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class SubbyService implements AutoCloseable {
    private final Platform platform;
    private final Path configFile, lecternFile, rulesFile;
    private final Ledger ledger;
    private final TwitchClient twitch;
    private final List<Platform.LecternTarget> lecterns = new ArrayList<>();
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Subby-Storage"); thread.setDaemon(true); return thread;
    });
    private volatile Config config;
    private volatile Rules rules;
    private record Pending(Rules.Action action, Subscriber subscriber) {}
    private final ArrayDeque<Pending> effects = new ArrayDeque<>();
    private final Map<Rules.Rule, Long> lastAction = new HashMap<>();
    private boolean paused;
    private long lastQueueWarning;
    private volatile boolean closed;

    public SubbyService(Path settingsDirectory, Path worldDirectory, Platform platform) throws IOException {
        this.platform = platform;
        configFile = settingsDirectory.resolve("config.json");
        config = JsonFiles.read(configFile, Config.class, Config.defaults());
        if (!Files.exists(configFile)) JsonFiles.write(configFile, config);
        rulesFile = settingsDirectory.resolve("rules.json");
        rules = JsonFiles.read(rulesFile, Rules.class, Rules.defaults());
        if (!Files.exists(rulesFile)) JsonFiles.write(rulesFile, rules);
        ledger = new Ledger(worldDirectory);
        lecternFile = worldDirectory.resolve("lecterns.json");
        Platform.LecternTarget[] saved = JsonFiles.read(lecternFile, Platform.LecternTarget[].class, new Platform.LecternTarget[0]);
        lecterns.addAll(Arrays.asList(saved));
        twitch = new TwitchClient(settingsDirectory, () -> config, this::record, platform::log);
        if (config.autoConnect()) twitch.connect();
    }
    private void record(Subscriber entry) {
        if (closed) return;
        try {
            if (ledger.append(entry)) {
                if (config.announceSubscribers()) platform.execute(() -> platform.announce(entry.name()));
                queueEffects(entry);
            }
        } catch (IOException e) {
            platform.log("Subscriber could not be saved: " + e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
    private synchronized void queueEffects(Subscriber entry) {
        long now = System.currentTimeMillis();
        for (Rules.Rule rule : rules.rules()) {
            if (!rule.event().equals(entry.kind()) || entry.months() < rule.minimumMonths() ||
                    now - lastAction.getOrDefault(rule, 0L) < rule.cooldownSeconds() * 1000L) continue;
            lastAction.put(rule, now);
            for (Rules.Action action : rule.actions()) {
                if (effects.size() < 256) effects.add(new Pending(action, entry));
                else if (now - lastQueueWarning > 60000) {
                    lastQueueWarning = now;
                    platform.log("Effect queue is full; extra effects were skipped. Every subscriber entry was saved.");
                }
            }
        }
    }
    public void tick() {
        if (closed) return;
        platform.refresh(ledger.book(0), List.copyOf(lecterns));
        for (int i = 0; i < 4; i++) {
            Pending next;
            synchronized (this) { next = paused ? null : effects.poll(); }
            if (next == null) break;
            try { platform.action(next.action, next.subscriber); }
            catch (RuntimeException e) { platform.log("A configured effect could not run. Check its sound/function ID."); }
        }
    }


    public void command(Platform.Audience audience, String[] args) {
        try {
            if (args.length == 0 || args[0].equals("help")) {
                audience.message("Subby: setup <clientId>, login, connect, disconnect, logout, status, book [volume], lectern bind|unbind, test [sub|resub|gift] <name>, supporter <name>, pause, resume, reload"); return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> { synchronized (this) { audience.message(twitch.status() + " | " + ledger.size() + " entries | " + ledger.book(0).volume() + " volumes | " + lecterns.size() + " lecterns | " + effects.size() + " effects queued" + (paused ? " (paused)" : "")); } }
                case "pause" -> { synchronized (this) { paused = true; } audience.message("Effects paused. Subscriber names keep recording."); }
                case "resume" -> { synchronized (this) { paused = false; } audience.message("Effects resumed; the queue will drain gradually."); }
                case "supporter" -> { if (args.length != 2) throw new IllegalArgumentException("Usage: /subby supporter <name>"); audience.message(ledger.supporter(args[1])); }
                case "book" -> platform.give(audience, ledger.book(args.length > 1 ? Integer.parseInt(args[1]) : 0), args.length == 1);
                case "connect" -> { twitch.connect(); audience.message("Connecting. Use /subby status to check Twitch."); }
                case "disconnect" -> { twitch.disconnect(); audience.message("Twitch disconnected until you reconnect or restart."); }
                case "logout" -> { twitch.logout(); audience.message("Local Twitch login removed. You can also revoke Subby in Twitch account connections."); }
                case "login" -> twitch.login(message -> reply(audience, message));
                case "setup" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Usage: /subby setup <public Twitch client ID>");
                    Config replacement = config.withClientId(args[1]);
                    commands.execute(() -> {
                        try { JsonFiles.write(configFile, replacement); config = replacement; twitch.disconnect(); reply(audience, "Client ID saved. Run /subby login."); }
                        catch (IOException e) { reply(audience, "Could not save config.json. Check file permissions."); }
                    });
                }
                case "reload" -> commands.execute(() -> {
                    try { Config replacement = JsonFiles.read(configFile, Config.class, Config.defaults()); Rules newRules = JsonFiles.read(rulesFile, Rules.class, Rules.defaults()); twitch.disconnect(); config = replacement; rules = newRules;
                        if (config.autoConnect()) twitch.connect(); reply(audience, "Subby configuration reloaded."); }
                    catch (IOException e) { reply(audience, "Invalid configuration; the previous settings remain active."); }
                });
                case "test" -> {
                    if (args.length != 2 && args.length != 3) throw new IllegalArgumentException("Usage: /subby test [sub|resub|gift] <name> (adds a permanent demo entry)");
                    String kind = args.length == 3 ? args[1] : "sub";
                    if (!Set.of("sub", "resub", "gift").contains(kind)) throw new IllegalArgumentException("Choose sub, resub, or gift.");
                    Subscriber demo = new Subscriber("demo-" + UUID.randomUUID(), "", "demo", args[args.length - 1], kind, "1000", kind.equals("resub") ? 12 : 1, Instant.now().toString());
                    commands.execute(() -> {
                        try { record(demo); reply(audience, "Demo entry recorded. Your live books will update."); }
                        catch (UncheckedIOException e) { twitch.disconnect(); reply(audience, "Entry was not recorded. Check the server log and storage permissions."); }
                    });
                }
                case "lectern" -> {
                    if (args.length != 2 || !(args[1].equals("bind") || args[1].equals("unbind"))) throw new IllegalArgumentException("Look at a lectern, then use /subby lectern bind|unbind.");
                    Platform.LecternTarget target = platform.targetedLectern(audience);
                    List<Platform.LecternTarget> replacement = new ArrayList<>(lecterns);
                    if (args[1].equals("bind")) { if (!replacement.contains(target)) replacement.add(target); }
                    else replacement.remove(target);

                    JsonFiles.write(lecternFile, replacement);
                    lecterns.clear(); lecterns.addAll(replacement);
                    audience.message(args[1].equals("bind") ? "Lectern bound. Leave it empty or insert a live Subby book; other books are preserved." : "Lectern unbound.");
                }
                default -> audience.message("Unknown Subby command. Use /subby help.");
            }
        } catch (NumberFormatException e) { audience.message("The volume must be a whole number."); }
        catch (IllegalArgumentException e) { audience.message(e.getMessage()); }
        catch (IOException e) { audience.message("Could not save lectern bindings. Check file permissions."); }
    }
    private void reply(Platform.Audience audience, String message) { platform.execute(() -> { if (!closed) audience.message(message); }); }
    @Override public void close() { closed = true; twitch.close(); commands.shutdown(); }
}
