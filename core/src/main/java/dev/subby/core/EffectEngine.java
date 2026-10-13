package dev.subby.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public final class EffectEngine {
    private record Pending(Rules.Action action, Subscriber event, int priority, String ruleId, long created, long due) {}
    private record Combo(long last, int count) {}
    private static final class State {
        int cursor;
        List<Pending> pending = new ArrayList<>();
        Map<String, Long> cooldowns = new HashMap<>();
        long lastDrain;
        Map<String, Combo> combos = new HashMap<>();
    }
    private final Path file;
    private final Supplier<Options> options;
    private final Supplier<Rules> rules;
    private final Platform platform;
    private final BiConsumer<Rules.Action, Subscriber> run;
    private final LongSupplier clock;
    private final Random random;
    private State state;
    private long minute, global, warning;
    private int items, mobs;
    private BooleanSupplier announcements = () -> false;
    public void announcements(BooleanSupplier enabled) { announcements = enabled; }

    public EffectEngine(Path directory, int existing, Supplier<Options> options, Supplier<Rules> rules, Platform platform,
                        BiConsumer<Rules.Action, Subscriber> run, LongSupplier clock, Random random) throws IOException {
        file = directory.resolve("effect-queue.json"); this.options = options; this.rules = rules; this.platform = platform; this.run = run; this.clock = clock; this.random = random;
        State fallback = new State(); fallback.cursor = existing;
        state = JsonFiles.read(file, State.class, fallback);
        if (state.cursor < 0 || state.cursor > existing || state.pending == null || state.pending.size() > 10000 || state.cooldowns == null) throw new IOException("Invalid saved effect queue; original data was preserved.");
        if (!Files.exists(file)) persist();
    }
    public synchronized void catchUp(List<Subscriber> entries) throws IOException {
        while (state.cursor < entries.size()) {
            String before = JsonFiles.GSON.toJson(state);
            try { enqueue(entries.get(state.cursor)); state.cursor++; persist(); }
            catch (IOException | RuntimeException e) { state = JsonFiles.GSON.fromJson(before, State.class); throw e; }
        }
    }
    private void enqueue(Subscriber entry) {
        Options config = options.get(); long now = clock.getAsLong();
        if (!config.advancedEnabled || blocked(config.moderation, entry)) return;
        String group = Set.of("sub", "resub", "gift").contains(entry.kind()) ? "subscriptions" : entry.kind();
        Combo old = state.combos.get(group);
        Combo combo = new Combo(now, old != null && config.queue.comboSeconds > 0 && now - old.last <= config.queue.comboSeconds * 1000L ? Math.min(10000, old.count + 1) : 1);
        state.combos.put(group, combo);
        Subscriber event = entry.withDetail("combo_count", entry.detail("subby_test").equals("true") ? Integer.toString(Math.max(1, entry.amount("combo_count"))) : Integer.toString(combo.count));
        if (announcements.getAsBoolean() && Set.of("sub", "resub", "gift").contains(event.kind()) && state.pending.size() < config.queue.capacity) state.pending.add(new Pending(new Rules.Action("message", "[Subby] {user} joined the subscriber book!"), event, 0, "__announcement", now, now));
        for (Rules.Rule rule : rules.get().rules()) {
            if (!rule.matches(event)) continue;
            boolean cooldown = now - state.cooldowns.getOrDefault(rule.conditions().id(), Long.MIN_VALUE / 2) < rule.cooldownSeconds() * 1000L;
            boolean combine = config.queue.combineEffects && config.queue.comboSeconds > 0;
            if (cooldown && !combine) continue;
            List<Rules.Action> actions = rule.actions();
            if (rule.conditions().randomPool() && !actions.isEmpty()) {
                Pending existing = combine ? state.pending.stream().filter(p -> Objects.equals(p.ruleId, rule.conditions().id()) && p.event.kind().equals(event.kind()) && now < p.due && p.event.detail("subby_test").equals(event.detail("subby_test"))).findFirst().orElse(null) : null;
                if (existing != null) actions = List.of(existing.action);
                else {
                int chosen = random.nextInt(actions.stream().mapToInt(Rules.Action::weight).sum());
                for (Rules.Action action : actions) { chosen -= action.weight(); if (chosen < 0) { actions = List.of(action); break; } }
                }
            }
            boolean added = false;
            for (Rules.Action action : actions) {
                boolean merged = false;
                if (combine) for (int i = 0; i < state.pending.size(); i++) {
                    Pending pending = state.pending.get(i);
                    if (Objects.equals(pending.ruleId, rule.conditions().id()) && pending.action.equals(action) && pending.event.kind().equals(event.kind()) && now < pending.due && pending.event.detail("subby_test").equals(event.detail("subby_test"))) {
                        state.pending.set(i, new Pending(action, event, pending.priority, pending.ruleId, pending.created, pending.due)); merged = true; break;
                    }
                }
                if (merged || cooldown) continue;
                if (state.pending.size() < config.queue.capacity) { state.pending.add(new Pending(action, event, rule.conditions().priority(), rule.conditions().id(), now, combine ? now + config.queue.comboSeconds * 1000L : now)); added = true; }
                else if (now - warning > 60000) { warning = now; platform.log("Effect queue full. Extra effects were skipped; subscriber names were saved."); }
            }
            if (added) state.cooldowns.put(rule.conditions().id(), now);
        }
        state.pending.sort(Comparator.comparingInt(Pending::priority).reversed());
    }
    private static boolean blocked(Options.Moderation moderation, Subscriber event) {
        return moderation.blockedUsers.stream().anyMatch(s -> s.equals(event.userId()) || s.equalsIgnoreCase(event.name()) || s.equalsIgnoreCase(event.detail("login")))
                || moderation.blockedWords.stream().anyMatch(s -> !s.isBlank() && event.name().toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT)));
    }
    public synchronized void tick(boolean paused) {
        Options config = options.get(); long now = clock.getAsLong();
        if (paused || !config.advancedEnabled || state.pending.isEmpty() || now - state.lastDrain < config.queue.intervalSeconds * 1000L || now - global < config.queue.globalCooldownSeconds * 1000L) return;
        if (now - minute >= 60000) { minute = now; items = 0; mobs = 0; }
        State before = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(state), State.class);
        state.lastDrain = now;
        int limit = config.queue.globalCooldownSeconds > 0 ? 1 : config.queue.effectsPerTick;
        for (int i = 0; i < limit && !state.pending.isEmpty(); i++) {
            if (state.pending.getFirst().due > now) break;
            Pending pending = state.pending.removeFirst();
            try {
                boolean current = pending.ruleId != null && (pending.ruleId.equals("__announcement") ? announcements.getAsBoolean() : rules.get().rules().stream().anyMatch(r -> r.conditions().id().equals(pending.ruleId) && r.conditions().enabled() && r.actions().contains(pending.action)));
                if (!current) continue;
                Subscriber publicEvent = Book.moderate(pending.event, config);
                if (publicEvent != null && config.moderation.minecraftFilter && !pending.event.userId().isBlank() && !config.moderation.filteredNames.containsKey(Book.filterKey(pending.event, config))) { state.pending.addFirst(pending); break; }
                if (publicEvent != null && allowed(pending.action, config)) { run.accept(pending.action, publicEvent); global = now; }
                else platform.log("Optional " + pending.action.type() + " effect skipped by player or safety settings.");
            } catch (RuntimeException e) { platform.log("Optional " + pending.action.type() + " effect failed; check its settings. Subscriber names are saved."); }
        }
        try { persist(); }
        catch (IOException e) { state = before; platform.log("Could not save the effect queue. Effects may repeat; check storage permissions."); }
    }
    private boolean allowed(Rules.Action action, Options config) {
        if (Set.of("message", "book").contains(action.type())) return true;
        Platform.PlayerState player = platform.playerState(config.targetPlayer);
        if (player == null || player.health() < config.safety.minimumHealth || config.safety.skipDuringBoss && player.boss()
                || !config.safety.allowedDimensions.isEmpty() && !config.safety.allowedDimensions.contains(player.dimension())) return false;
        if (ActionCommands.dangerous(action) && (!config.safety.destructiveActions || config.safety.hardcoreSafe && player.hardcore())) return false;
        if (Set.of("function", "command").contains(action.type()) && !config.safety.allowedCommands.contains(action.type().equals("function") ? "function " + action.value() : action.value())) return false;
        if (Set.of("give", "chest", "token").contains(action.type())) {
            int amount = action.type().equals("token") ? 1 : Math.min(64, action.number("amount", 1, 1, 4096));
            if (items + amount > config.safety.itemsPerMinute) return false; items += amount;
        }
        if (action.type().equals("mob")) { if (mobs >= config.safety.mobsPerMinute) return false; mobs++; }
        return true;
    }
    public synchronized int size() { return state.pending.size(); }
    public synchronized int test(Subscriber event) throws IOException {
        if (!options.get().advancedEnabled) return 0;
        State before = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(state), State.class);
        try {
            int previous = state.pending.size(); state.cooldowns = new HashMap<>(); enqueue(event.withDetail("subby_test", "true"));
            state.cooldowns = before.cooldowns; state.combos = before.combos; persist(); return state.pending.size() - previous;
        } catch (IOException | RuntimeException e) { state = before; throw e; }
    }
    public synchronized void clear() throws IOException { State before = state; State replacement = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(state), State.class); replacement.pending.clear(); state = replacement; try { persist(); } catch (IOException e) { state = before; throw e; } }
    private void persist() throws IOException {
        if (options.get().queue.persistent) {
            State saved = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(state), State.class);
            saved.pending.removeIf(p -> p.event.detail("subby_test").equals("true")); JsonFiles.write(file, saved);
        }
        else { State metadata = new State(); metadata.cursor = state.cursor; JsonFiles.write(file, metadata); }
    }
}
