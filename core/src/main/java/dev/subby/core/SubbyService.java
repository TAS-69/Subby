package dev.subby.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class SubbyService implements AutoCloseable {
    private final Platform platform;
    private final Path configFile, lecternFile, rulesFile, optionsFile, connectionFile, runtimeFile, presetTransaction;
    private final Ledger ledger;
    private final TwitchClient twitch;
    private final CompanionClient companion;
    private volatile ConnectionSettings connection;
    private final List<Platform.LecternTarget> lecterns = new ArrayList<>();
    private final List<Binding> bindings = new ArrayList<>();
    private final Path bindingsFile, physicalFile;
    private static final class PhysicalState {
        Map<String, Integer> archives = new HashMap<>();
        Map<String, Long> pulses = new HashMap<>();
    }
    private final PhysicalState physical;
    private final Map<String, Long> bindingWarnings = new HashMap<>();
    private record NameFilters(Map<String, String> names) { NameFilters { names = Map.copyOf(names); } }
    private final Path filtersFile;
    private final Set<String> pendingFilters = ConcurrentHashMap.newKeySet();
    private final ExecutorService commands = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Subby-Storage"); thread.setDaemon(true); return thread;
    });
    private volatile Config config;
    private volatile Rules rules;
    private volatile Options options;
    private final Object settingsLock = new Object();
    private final UiController ui;
    private final Presets presets;
    private Book cachedBook;
    private int cachedSize = -1;
    private Options cachedOptions;
    private final EffectEngine effects;
    private boolean paused;
    private volatile boolean closed;

    public SubbyService(Path settingsDirectory, Path worldDirectory, Platform platform) throws IOException {
        this.platform = platform;
        presets = new Presets(settingsDirectory);
        configFile = settingsDirectory.resolve("config.json");
        presetTransaction = settingsDirectory.resolve("preset-transaction.json");
        if (Files.exists(presetTransaction)) {
            Presets.Preset pending = JsonFiles.read(presetTransaction, Presets.Preset.class, null);
            JsonFiles.write(settingsDirectory.resolve("options.json"), pending.options()); JsonFiles.write(settingsDirectory.resolve("rules.json"), pending.rules()); Files.delete(presetTransaction);
        }
        config = JsonFiles.read(configFile, Config.class, Config.defaults());
        if (!Files.exists(configFile)) JsonFiles.write(configFile, config);
        rulesFile = settingsDirectory.resolve("rules.json");
        rules = JsonFiles.read(rulesFile, Rules.class, Rules.defaults());
        JsonFiles.write(rulesFile, rules);
        optionsFile = settingsDirectory.resolve("options.json");
        options = JsonFiles.read(optionsFile, Options.class, new Options());
        try { options.validate(); } catch (RuntimeException e) { throw new IOException("Invalid options.json", e); }
        if (!Files.exists(optionsFile)) JsonFiles.write(optionsFile, options);
        connectionFile = settingsDirectory.resolve("connection.json");
        ConnectionSettings defaults = config.clientId().isEmpty() ? ConnectionSettings.defaults() : new ConnectionSettings("direct", "http://localhost:3030", "Minecraft", config.autoConnect());
        connection = JsonFiles.read(connectionFile, ConnectionSettings.class, defaults);
        if (!Files.exists(connectionFile)) JsonFiles.write(connectionFile, connection);
        runtimeFile = worldDirectory.resolve("runtime.json");
        paused = JsonFiles.read(runtimeFile, Boolean.class, false);
        ledger = new Ledger(worldDirectory);
        filtersFile = worldDirectory.resolve("name-filters.json");
        options.moderation.filteredNames = JsonFiles.read(filtersFile, NameFilters.class, new NameFilters(Map.of())).names();
        effects = new EffectEngine(worldDirectory, ledger.size(), () -> options, () -> rules, platform, this::runAction, System::currentTimeMillis, new Random());
        effects.announcements(() -> config.announceSubscribers());
        effects.catchUp(ledger.entries());
        lecternFile = worldDirectory.resolve("lecterns.json");
        Platform.LecternTarget[] saved = JsonFiles.read(lecternFile, Platform.LecternTarget[].class, new Platform.LecternTarget[0]);
        lecterns.addAll(Arrays.asList(saved));
        bindingsFile = worldDirectory.resolve("bindings.json"); physicalFile = worldDirectory.resolve("physical-state.json");
        bindings.addAll(Arrays.asList(JsonFiles.read(bindingsFile, Binding[].class, new Binding[0])));
        if (bindings.size() > 64) throw new IOException("Too many physical bindings");
        physical = JsonFiles.read(physicalFile, PhysicalState.class, new PhysicalState());
        if (physical.archives == null || physical.pulses == null) throw new IOException("Invalid physical binding state");
        twitch = new TwitchClient(settingsDirectory, () -> config, this::record, platform::log);
        companion = new CompanionClient(settingsDirectory, () -> connection, this::requestedEvents, this::record, platform::log);
        companion.onLinked(() -> companion.bot(options.bot.enabled && options.advancedEnabled, options.bot.style, options.bot.template, platform::log));
        companion.expectedChannel(ledger::broadcasterId);
        ui = new UiController(this, platform);
        if (connection.mode().equals("direct") && config.autoConnect()) twitch.connect();
        else if (connection.autoConnect()) companion.resume();
    }
    private void record(Subscriber entry) {
        if (closed) throw new IllegalStateException("Subby is closed");
        try {
            if (entry.kind().equals("control")) {
                if (!Set.of("pause", "resume").contains(entry.detail("command"))) throw new IOException("Invalid control event");
                ledger.append(entry);
                setPaused(entry.detail("command").equals("pause"));
                effects.catchUp(ledger.entries());
                return;
            }
            boolean added = ledger.append(entry);
            if (options.moderation.respectBans && Set.of("ban", "unban").contains(entry.kind())) {
                synchronized (settingsLock) {
                Options replacement = options.copy();
                if (entry.kind().equals("ban")) {
                    replacement.moderation.bannedUsers.add(entry.userId());
                    long expiry = Long.MAX_VALUE;
                    try { if (!entry.detail("ban_expires").isBlank()) expiry = Instant.parse(entry.detail("ban_expires")).toEpochMilli(); } catch (java.time.format.DateTimeParseException ignored) {}
                    replacement.moderation.banExpiries.put(entry.userId(), expiry);
                } else { replacement.moderation.bannedUsers.remove(entry.userId()); replacement.moderation.banExpiries.remove(entry.userId()); }
                JsonFiles.write(optionsFile, replacement); options = replacement;
                }
            }
            effects.catchUp(ledger.entries());
            if (added) platform.execute(() -> SubbyEvents.publish(ledger.libraryId(), entry, platform::log));
        } catch (IOException e) {
            platform.log("Subscriber could not be saved: " + e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
    private void runAction(Rules.Action action, Subscriber event) {
        switch (action.type()) {
            case "book" -> {
                if (event.detail("subby_test").equals("true")) {
                    Subscriber sample = new Subscriber("preview", "", "", action.message(event), "entry", event.tier(), event.months(), event.receivedAt(), event.details());
                    platform.giveNamed(options.targetPlayer, Book.create("preview-" + ledger.libraryId(), List.of(sample), 0, options).preview()); return;
                }
                if (!event.subscription()) {
                    Subscriber original = ledger.entries().stream().filter(e -> e.messageId().equals(event.messageId())).findFirst().orElse(event);
                    Map<String, String> details = new HashMap<>(original.details()); details.put("book_template", action.value());
                    record(new Subscriber(event.messageId() + "-book", event.broadcasterId(), event.userId(), original.name(), "entry", event.tier(), event.months(), event.receivedAt(), details));
                }
            }
            case "token" -> {
                Book token = Book.keepsake("token-" + event.messageId(), event.name(), action.message(event));
                if (event.detail("subby_test").equals("true")) token = token.preview();
                String destination = action.parameters().getOrDefault("chest", "");
                if (destination.isBlank()) platform.giveNamed(options.targetPlayer, token);
                else {
                    Binding binding = bindings.stream().filter(b -> b.name().equals(destination) && Set.of("archive", "chest").contains(b.kind())).findFirst().orElseThrow(() -> new IllegalArgumentException("Supporter chest not bound"));
                    if (!platform.depositArchive(binding, token)) throw new IllegalArgumentException("Supporter chest full or unavailable");
                }
            }
            case "chest", "sign", "display", "redstone" -> {
                Binding binding = bindings.stream().filter(b -> b.name().equals(action.value()) && b.kind().equals(action.type())).findFirst().orElseThrow(() -> new IllegalArgumentException("Physical binding not found"));
                if (action.type().equals("chest")) { if (!platform.depositItem(binding, action.parameters().getOrDefault("item", "minecraft:diamond"), Math.min(64, action.number("amount", 1, 1, 4096)))) throw new IllegalArgumentException("Chest full or unavailable"); }
                else if (action.type().equals("redstone")) {
                    physical.pulses.put(binding.name(), System.currentTimeMillis() + 1000);
                    try { JsonFiles.write(physicalFile, physical); } catch (IOException e) { physical.pulses.remove(binding.name()); throw new UncheckedIOException(e); }
                    platform.pulse(binding, true);
                } else platform.board(binding, event.render(action.parameters().getOrDefault("text", "{user}")), ledger.libraryId());
            }
            default -> platform.action(action, event, options.targetPlayer);
        }
    }
    public void tick() {
        if (closed) return;
        platform.refresh(currentBook(), List.copyOf(lecterns), options.book.boundPlayer);
        tickFilters();
        tickBindings();
        effects.tick(paused());
    }
    private void tickFilters() {
        if (!options.moderation.minecraftFilter || !platform.nameFilterAvailable(options.targetPlayer) || pendingFilters.size() >= 4) return;
        for (Subscriber event : ledger.entries()) {
            String key = Book.filterKey(event, options);
            if (event.userId().isBlank() || event.kind().equals("control") || options.moderation.filteredNames.containsKey(key) || !pendingFilters.add(key)) continue;
            String input = key.substring(key.indexOf(':') + 1);
            platform.filterName(input, options.targetPlayer).orTimeout(15, TimeUnit.SECONDS).whenComplete((filtered, failure) -> {
                if (closed) { pendingFilters.remove(key); return; }
                if (failure != null) { pendingFilters.remove(key); return; }
                commands.execute(() -> {
                    try { synchronized (settingsLock) {
                        Map<String, String> names = new HashMap<>(options.moderation.filteredNames); names.put(key, filtered.isBlank() ? "" : Subscriber.safeName(filtered));
                        JsonFiles.write(filtersFile, new NameFilters(names)); Options replacement = options.copy(); replacement.moderation.filteredNames = Map.copyOf(names); options = replacement;
                    } } catch (IOException e) { platform.log("Filtered names could not be saved. Raw names remain hidden until filtering succeeds."); }
                    finally { pendingFilters.remove(key); }
                });
            });
            if (pendingFilters.size() >= 4) break;
        }
    }
    public boolean nameFilterAvailable() { return platform.nameFilterAvailable(options.targetPlayer); }
    private void tickBindings() {
        boolean changed = false;
        for (Binding binding : bindings) if (physical.pulses.containsKey(binding.name()) && physical.pulses.get(binding.name()) <= System.currentTimeMillis() && platform.pulse(binding, false)) { physical.pulses.remove(binding.name()); changed = true; }
        if (!bindings.isEmpty()) {
            List<Subscriber> entries = ledger.entries().stream().map(e -> Book.moderate(e, options)).filter(Objects::nonNull).toList();
            SupporterRegistry registry = new SupporterRegistry(); entries.forEach(registry::record);
            int wallPage = 0;
            for (Binding binding : bindings) {
                try {
                if (binding.kind().equals("archive")) {
                    int next = currentBook().volume() < 2 ? 1 : physical.archives.getOrDefault(binding.name(), 0) % (currentBook().volume() - 1) + 1;
                    if (next < currentBook().volume() && platform.depositArchive(binding, Book.create(ledger.libraryId(), ledger.entries(), next, options))) { physical.archives.put(binding.name(), next); changed = true; }
                } else if (binding.kind().equals("live_chest")) platform.liveChest(binding, currentBook());
                else if (options.advancedEnabled && Set.of("sign", "display").contains(binding.kind()) && !binding.mode().equals("manual")) platform.board(binding, binding.board(entries, registry.all(), binding.mode().equals("wall") ? wallPage++ : 0), ledger.libraryId());
                } catch (RuntimeException e) {
                    long now = System.currentTimeMillis();
                    if (now - bindingWarnings.getOrDefault(binding.name(), 0L) > 60000) { platform.log("Physical binding " + binding.name() + " could not update. Check that its target still exists."); bindingWarnings.put(binding.name(), now); }
                }
            }
        }
        if (changed) try { JsonFiles.write(physicalFile, physical); } catch (IOException e) { platform.log("Physical binding progress could not be saved. Existing chest contents are preserved."); }
    }
    public List<Binding> bindings() { return List.copyOf(bindings); }
    public void bind(Platform.Audience audience, String name, String kind, String mode) {
        if (!audience.permitted("subby.bind")) { audience.message("You do not have permission to bind locations."); return; }
        try {
            Binding binding = platform.targetedBinding(audience, name, kind, mode);
            if (bindings.size() >= 64 || bindings.stream().anyMatch(b -> b.name().equals(name))) throw new IllegalArgumentException("Choose an unused binding name; at most 64 bindings are allowed.");
            List<Binding> replacement = new ArrayList<>(bindings); replacement.add(binding); JsonFiles.write(bindingsFile, replacement); bindings.add(binding);
            audience.message("Bound " + name + ". Books keep updating; optional boards and effects require Advanced interactions.");
        } catch (IOException | IllegalArgumentException e) { audience.message("Could not bind: " + e.getMessage()); }
    }
    public void unbind(Platform.Audience audience, String name) {
        if (!audience.permitted("subby.bind")) { audience.message("You do not have permission to unbind locations."); return; }
        try {
            Binding binding = bindings.stream().filter(b -> b.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("Binding not found"));
            if (physical.pulses.containsKey(name) && !platform.pulse(binding, false)) throw new IllegalArgumentException("Load the bound button before removing this binding so it can reset safely.");
            List<Binding> replacement = new ArrayList<>(bindings); replacement.remove(binding); JsonFiles.write(bindingsFile, replacement); bindings.remove(binding);
            physical.pulses.remove(name); physical.archives.remove(name); JsonFiles.write(physicalFile, physical); audience.message("Binding removed. Existing blocks, books, and display text are kept.");
        } catch (IOException | IllegalArgumentException e) { audience.message("Could not remove binding: " + e.getMessage()); }
    }
    public Config config() { return config; }
    private Set<String> requestedEvents() {
        Options current = options; Set<String> selected = new LinkedHashSet<>(current.advancedEnabled ? current.events : Set.of());
        if (current.moderation.respectBans) selected.addAll(Set.of("ban", "unban"));
        return Set.copyOf(selected);
    }
    public Options options() { return options.copy(); }
    public Rules rules() { return rules; }
    public String connectionStatus() { return connection.mode().equals("companion") ? companion.status() : twitch.status(); }
    public ConnectionSettings connection() { return connection; }
    public void saveConnection(ConnectionSettings replacement, Platform.Audience audience, Runnable done) {
        commands.execute(() -> {
            try {
                JsonFiles.write(connectionFile, replacement); twitch.disconnect(); companion.disconnect(); connection = replacement;
                platform.execute(done);
            } catch (IOException e) { reply(audience, "Could not save connection settings."); }
        });
    }
    private Platform.Audience asynchronousAudience(Platform.Audience audience) {
        return new Platform.Audience() {
            public void message(String text) { reply(audience, text); }
            @Override public void link(String label, String url) { platform.execute(() -> { if (!closed) audience.link(label, url); }); }
        };
    }
    public void acceptEvent(Subscriber event) { record(event); }
    public void recover(Platform.Audience audience) {
        if (!audience.permitted("subby.connect")) { audience.message("You do not have permission to recover subscribers."); return; }
        if (connection.mode().equals("companion")) companion.recover(asynchronousAudience(audience)); else audience.message("Current subscriber recovery requires Companion mode.");
    }
    public void updateBot(Platform.Audience audience) { companion.bot(options.bot.enabled && options.advancedEnabled, options.bot.style, options.bot.template, asynchronousAudience(audience)); }
    public void exportPreset(String name, Platform.Audience audience) {
        commands.execute(() -> { try { presets.export(name, options, rules); reply(audience, "Preset saved as presets/" + name + ".json. Credentials and private user lists are excluded."); } catch (IOException | IllegalArgumentException e) { reply(audience, "Could not export preset: " + e.getMessage()); } });
    }
    public void importPreset(String name, boolean builtIn, Platform.Audience audience, Runnable done) {
        commands.execute(() -> {
            try {
                Presets.Preset preset = builtIn ? Presets.builtIn(name) : presets.read(name);
                JsonFiles.write(optionsFile.resolveSibling("options.backup.json"), options);
                JsonFiles.write(rulesFile.resolveSibling("rules.backup.json"), rules);
                JsonFiles.write(presetTransaction, preset);
                JsonFiles.write(optionsFile, preset.options()); JsonFiles.write(rulesFile, preset.rules());
                Files.delete(presetTransaction);
                options = preset.options(); rules = preset.rules();
                reply(audience, "Preset applied. Advanced interactions remain off; review rules, select a player, and enable them when ready.");
                platform.execute(done);
            } catch (IOException | IllegalArgumentException e) { reply(audience, "Could not import preset. Previous settings were backed up: " + e.getMessage()); }
        });
    }
    public void dryRun(Platform.Audience audience, Subscriber event) {
        if (!audience.permitted("subby.test")) { audience.message("You do not have permission to test this profile."); return; }
        audience.message("Preview: " + event.kind() + ". No names saved and no effects executed.");
        if (!options.advancedEnabled) audience.message("Advanced interactions are off; these rules would remain inactive.");
        List<Rules.Rule> matches = rules.rules().stream().filter(rule -> rule.matches(event)).toList();
        audience.message(matches.size() + " matching rules (cooldowns and safety checks may still skip effects).");
        for (Rules.Rule rule : matches) for (Rules.Action action : rule.actions()) audience.message(action.type() + ": " + action.message(event) + (rule.conditions().randomPool() ? " (random pool, weight " + action.weight() + ")" : ""));
    }
    public static Subscriber sample(String kind, String name, int amount, String reward, int tier, int combo) {
        if (!EventType.valid(kind) || amount < 0 || amount > 1000000 || tier < 1 || tier > 3 || combo < 1 || combo > 10000) throw new IllegalArgumentException("Invalid test event.");
        Map<String, String> details = new HashMap<>();
        for (String key : List.of("bits", "gift_count", "raid_viewers", "hype_level")) details.put(key, Integer.toString(amount));
        details.put("reward", reward); details.put("reward_id", reward); details.put("combo_count", Integer.toString(combo));
        if (kind.equals("gift_batch")) details.put("gifter", name);
        return new Subscriber("test-" + UUID.randomUUID(), "", "", name, kind, tier + "000", kind.equals("resub") ? Math.max(1, Math.min(10000, amount)) : 1, Instant.now().toString(), details);
    }
    public void runTest(Platform.Audience audience, Subscriber event) {
        if (!audience.permitted("subby.test")) { audience.message("You do not have permission to test this profile."); return; }
        if (!options.advancedEnabled) { audience.message("Enable Advanced interactions before running test effects. Preview works while they are off."); return; }
        try { audience.message(effects.test(event) + " test effects queued. Normal safety limits apply. No fake subscriber entries are saved."); }
        catch (IOException e) { audience.message("Could not prepare test effects. Check storage permissions."); }
    }
    public List<String> dashboard() {
        Book book = currentBook();
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        long count = ledger.entries().stream().filter(e -> e.receivedAt().startsWith(today)).count();
        return List.of(connectionStatus(), book.entries() + " book entries", book.volume() + " volumes", ledger.supporters().size() + " people recorded", effects.size() + " effects queued", paused() ? "Effects paused; names keep recording" : "Effects " + (options.advancedEnabled ? "enabled" : "off"), lecterns.size() + " lecterns", bindings.size() + " physical locations", count + " events today (UTC)", book.pages().size() + "/100 pages filled in this volume");
    }
    public String canonicalUser(String input) {
        String text = input.trim();
        return ledger.entries().stream().filter(e -> !e.userId().isBlank() && (e.userId().equals(text) || e.name().equalsIgnoreCase(text) || e.detail("login").equalsIgnoreCase(text))).reduce((first, last) -> last).map(Subscriber::userId).orElse(text);
    }
    private synchronized void setPaused(boolean value) throws IOException { JsonFiles.write(runtimeFile, value); paused = value; }
    public synchronized boolean paused() { return paused; }
    public synchronized Book currentBook() {
        if (cachedBook == null || cachedSize != ledger.size() || cachedOptions != options) {
            cachedBook = Book.create(ledger.libraryId(), ledger.entries(), 0, options);
            cachedSize = ledger.size(); cachedOptions = options;
        }
        return cachedBook;
    }
    public boolean captureChat(String id, String message) { return ui.captureChat(id, message); }
    public void cancelInput(String id) { ui.cancelInput(id); }
    public List<Platform.LecternTarget> boundLecterns() { return List.copyOf(lecterns); }
    public void saveOptions(Consumer<Options> edit, Platform.Audience audience, Runnable done) {
        commands.execute(() -> {
            try {
                synchronized (settingsLock) {
                Options previous = options; Options replacement = previous.copy(); edit.accept(replacement); replacement.validate();
                JsonFiles.write(optionsFile, replacement); options = replacement;
                if (connection.mode().equals("companion") && (previous.bot.enabled || replacement.bot.enabled)) updateBot(audience);
                }
                platform.execute(() -> { if (!closed) done.run(); });
            } catch (IOException | IllegalArgumentException e) { reply(audience, "Could not save: " + e.getMessage()); }
        });
    }
    public void saveRules(UnaryOperator<Rules> edit, Platform.Audience audience, Runnable done) {
        commands.execute(() -> {
            try {
                Rules replacement = edit.apply(rules); JsonFiles.write(rulesFile, replacement); rules = replacement;
                platform.execute(() -> { if (!closed) done.run(); });
            } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) { reply(audience, "Could not save this rule: " + e.getMessage()); }
        });
    }
    public void saveConfig(Config replacement, Platform.Audience audience, Runnable done) {
        commands.execute(() -> {
            try { JsonFiles.write(configFile, replacement); config = replacement; twitch.disconnect(); platform.execute(done); }
            catch (IOException e) { reply(audience, "Could not save Twitch settings."); }
        });
    }
    public void preview(Platform.Audience audience) {
        List<Subscriber> sample = List.of(new Subscriber("preview-1", "", "preview-1", "ExampleViewer", "sub", "1000", 1, Instant.now().toString(), Map.of("login", "exampleviewer")),
                new Subscriber("preview-2", "", "preview-2", "ReturningViewer", "resub", "2000", 14, Instant.now().toString(), Map.of("login", "returningviewer")),
                new Subscriber("preview-3", "", "preview-3", "GiftRecipient", "gift", "1000", 1, Instant.now().toString(), Map.of("login", "giftrecipient")));
        Options previewOptions = options.copy(); previewOptions.moderation.minecraftFilter = false;
        Book book = Book.create("preview-" + ledger.libraryId(), sample, 0, previewOptions);
        platform.give(audience, book.preview(), false);
        audience.message("Preview created. No sample names were saved to your real subscriber library.");
    }


    public void command(Platform.Audience audience, String[] args) {
        try {
            String permission = args.length == 0 ? "use" : switch (args[0].toLowerCase(Locale.ROOT)) {
                case "connect", "disconnect", "logout", "login", "setup" -> "connect";
                case "lectern", "bind", "unbind" -> "bind"; case "pause", "resume" -> "pause"; case "test" -> "test";
                case "remove", "restore" -> "moderate"; case "recover" -> "connect";
                case "reload", "preset" -> "configure"; case "menu" -> "use"; default -> "book";
            };
            if (!audience.permitted("subby." + permission)) { audience.message("You do not have permission for this profile action."); return; }
            if (args.length == 0 || args[0].equals("menu")) { ui.home(audience); return; }
            if (args[0].equals("help")) {
                audience.message("Use /subby for the visual menu. Commands: connect, disconnect, logout, status, book [volume], preview, lectern bind|unbind, pause, resume, supporter <name>, remove|restore <name>, test <event> [name] [amount], recover, profile, preset, reload. Direct Twitch setup is under Advanced."); return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "status" -> audience.message(connectionStatus() + " | " + ledger.size() + " events | " + currentBook().volume() + " volumes | " + lecterns.size() + " lecterns | " + effects.size() + " effects queued" + (paused() ? " (paused)" : ""));
                case "pause" -> { setPaused(true); audience.message("Effects paused. Subscriber names keep recording."); }
                case "resume" -> { setPaused(false); audience.message("Effects resumed; the queue will drain gradually."); }
                case "supporter" -> { if (args.length != 2) throw new IllegalArgumentException("Usage: /subby supporter <name>"); audience.message(ledger.supporter(args[1])); }
                case "recover" -> recover(audience);
                case "bind" -> { if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Usage: /subby bind <archive|chest|sign|display|redstone> <name> [latest|recent|top_gifters|top_bits|manual]"); bind(audience, args[2], args[1], args.length == 4 ? args[3] : "manual"); }
                case "unbind" -> { if (args.length != 2) throw new IllegalArgumentException("Usage: /subby unbind <name>"); unbind(audience, args[1]); }
                case "remove", "restore" -> {
                    if (args.length != 2) throw new IllegalArgumentException("Usage: /subby remove|restore <Twitch name or user ID>");
                    String user = canonicalUser(args[1]);
                    saveOptions(o -> { if (args[0].equalsIgnoreCase("remove")) o.moderation.removedUsers.add(user); else { o.moderation.removedUsers.removeIf(v -> v.equalsIgnoreCase(user) || v.equalsIgnoreCase(args[1])); o.moderation.blockedUsers.removeIf(v -> v.equalsIgnoreCase(user) || v.equalsIgnoreCase(args[1])); } }, audience, () -> audience.message("Name moderation saved. Live books and boards will refresh."));
                }
                case "preset" -> {
                    if (args.length != 3) throw new IllegalArgumentException("Usage: /subby preset export|import|builtin <name>");
                    if (args[1].equals("export")) exportPreset(args[2], audience);
                    else if (Set.of("import", "builtin").contains(args[1])) importPreset(args[2], args[1].equals("builtin"), audience, () -> {});
                    else throw new IllegalArgumentException("Choose export, import, or builtin.");
                }
                case "book" -> {
                    if (args.length == 1 && !options.book.boundPlayer.isBlank()) { platform.giveNamed(options.book.boundPlayer, currentBook(), true); audience.message("Live book given to " + options.book.boundPlayer + "."); }
                    else platform.give(audience, args.length > 1 ? Book.create(ledger.libraryId(), ledger.entries(), Integer.parseInt(args[1]), options) : currentBook(), args.length == 1);
                }
                case "preview" -> preview(audience);
                case "connect" -> {
                    if (connection.mode().equals("companion")) companion.connect(asynchronousAudience(audience));
                    else twitch.connect();
                    audience.message("Connecting. Use /subby status to check the connection.");
                }
                case "disconnect" -> { twitch.disconnect(); companion.disconnect(); audience.message("Disconnected until you reconnect or restart. Names already saved stay in your library."); }
                case "logout" -> {
                    if (connection.mode().equals("companion")) companion.logout(asynchronousAudience(audience));
                    else { twitch.logout(); audience.message("Local Twitch login removed. You can also revoke Subby in Twitch account connections."); }
                }
                case "login" -> { if (!connection.mode().equals("direct")) throw new IllegalArgumentException("Use /subby connect for Companion browser pairing. Direct login is under Advanced > Connection settings."); twitch.login(message -> reply(audience, message)); }
                case "setup" -> {
                    if (!connection.mode().equals("direct")) throw new IllegalArgumentException("Public application setup is only used in Advanced > Direct mode. Companion uses /subby connect.");
                    if (args.length != 2) throw new IllegalArgumentException("Usage: /subby setup <public Twitch client ID>");
                    Config replacement = config.withClientId(args[1]);
                    commands.execute(() -> {
                        try { JsonFiles.write(configFile, replacement); config = replacement; twitch.disconnect(); reply(audience, "Client ID saved. Run /subby login."); }
                        catch (IOException e) { reply(audience, "Could not save config.json. Check file permissions."); }
                    });
                }
                case "reload" -> commands.execute(() -> {
                    try {
                        Config replacement = JsonFiles.read(configFile, Config.class, Config.defaults()); Rules newRules = JsonFiles.read(rulesFile, Rules.class, Rules.defaults()); Options newOptions = JsonFiles.read(optionsFile, Options.class, new Options()); newOptions.validate(); ConnectionSettings newConnection = JsonFiles.read(connectionFile, ConnectionSettings.class, ConnectionSettings.defaults());
                        newOptions.moderation.filteredNames = options.moderation.filteredNames;
                        twitch.disconnect(); companion.disconnect(); config = replacement; rules = newRules; options = newOptions; connection = newConnection;
                        if (connection.mode().equals("direct") && config.autoConnect()) twitch.connect(); else if (connection.autoConnect()) companion.resume();
                        reply(audience, "Subby configuration reloaded.");
                    }
                    catch (IOException | IllegalArgumentException e) { reply(audience, "Invalid configuration; the previous settings remain active."); }
                });
                case "test" -> {
                    if (args.length < 2 || args.length > 4) throw new IllegalArgumentException("Usage: /subby test <event> [name] [amount] (preview only)");
                    boolean explicit = EventType.valid(args[1]); String kind = explicit ? args[1] : "sub";
                    String name = explicit ? args.length > 2 && !kind.equals("reward") ? args[2] : "ExampleViewer" : args[1];
                    int amount = args.length == 4 ? Integer.parseInt(args[3]) : kind.equals("resub") ? 12 : 1;
                    Subscriber demo = sample(kind, name, amount, kind.equals("reward") && args.length > 2 ? args[2] : "", 1, 1);
                    if (demo.subscription()) platform.give(audience, Book.create("preview-" + ledger.libraryId(), List.of(demo), 0, options).preview(), false);
                    dryRun(audience, demo);
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
        } catch (NumberFormatException e) { audience.message("Enter a whole number for the volume or test amount."); }
        catch (IllegalArgumentException e) { audience.message(e.getMessage()); }
        catch (IOException e) { audience.message("Could not save lectern bindings. Check file permissions."); }
    }
    private void reply(Platform.Audience audience, String message) { platform.execute(() -> { if (!closed) audience.message(message); }); }
    @Override public void close() {
        closed = true; companion.close(); twitch.close(); commands.shutdown();
        try { if (!commands.awaitTermination(5, TimeUnit.SECONDS)) commands.shutdownNow(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); commands.shutdownNow(); }
    }
}
