package dev.subby.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EffectEngineTest {
    @TempDir Path directory;
    @Test void thresholdsCooldownsWeightedPoolsPriorityAndPacingUseTheSavedQueue() throws Exception {
        Options options = new Options(); options.advancedEnabled = true; options.queue.effectsPerTick = 1;
        AtomicLong now = new AtomicLong(100000);
        Rules.Rule low = new Rules.Rule("bits", 0, 60, List.of(new Rules.Action("message", "low")), new Rules.Conditions("low", true, 0, 100, "", 0, false));
        Rules.Rule high = new Rules.Rule("bits", 0, 0, List.of(new Rules.Action("message", "high-one", Map.of(), 1), new Rules.Action("message", "high-two", Map.of(), 100)), new Rules.Conditions("high", true, 0, 100, "", 10, true));
        Rules rules = new Rules(List.of(low, high)); List<String> actions = new ArrayList<>();
        EffectEngine engine = new EffectEngine(directory, 0, () -> options, () -> rules, new ServiceTest.FakePlatform(), (a, e) -> actions.add(a.message(e)), now::get, new Random(4));
        Subscriber event = new Subscriber("id", "123", "user", "Viewer", "bits", "1000", 1, "now", Map.of("bits", "100"));
        engine.catchUp(List.of(event)); assertEquals(2, engine.size()); engine.tick(true); assertTrue(actions.isEmpty());
        engine.tick(false); assertEquals(List.of("high-two"), actions); engine.tick(false); assertEquals(1, actions.size());
        now.addAndGet(1000); engine.tick(false); assertEquals(List.of("high-two", "low"), actions);
        engine.catchUp(List.of(event, event.withDetail("bits", "99"))); assertEquals(0, engine.size());
        engine.catchUp(List.of(event, event.withDetail("bits", "99"), event)); assertEquals(1, engine.size());
        EffectEngine reopened = new EffectEngine(directory, 3, () -> options, () -> rules, new ServiceTest.FakePlatform(), (a, e) -> actions.add(a.message(e)), now::get, new Random(4));
        assertEquals(1, reopened.size());
    }
    @Test void playerSafetyAndItemLimitsDoNotAffectSubscriberStorage() throws Exception {
        Options options = new Options(); options.advancedEnabled = true; options.targetPlayer = "Creator"; options.safety.itemsPerMinute = 1;
        AtomicLong now = new AtomicLong(100000); List<String> ran = new ArrayList<>();
        Rules rules = new Rules(List.of(new Rules.Rule("sub", 0, 0, List.of(new Rules.Action("give", "minecraft:diamond"), new Rules.Action("give", "minecraft:diamond"), new Rules.Action("mob", "minecraft:zombie"), new Rules.Action("command", "weather clear")))));
        Platform platform = new ServiceTest.FakePlatform() { @Override public PlayerState playerState(String name) { return new PlayerState(name, "minecraft:overworld", 20, true, false); } };
        EffectEngine engine = new EffectEngine(directory, 0, () -> options, () -> rules, platform, (a, e) -> ran.add(a.type()), now::get, new Random());
        engine.catchUp(List.of(new Subscriber("id", "123", "user", "Viewer", "sub", "1000", 1, "now"))); engine.tick(false);
        assertEquals(List.of("give"), ran); assertEquals(0, engine.size());
    }
    @Test void viewerTextCannotBecomeCommandSyntaxOrRecursivePlaceholders() {
        Subscriber event = new Subscriber("id", "123", "user", "{bits}", "sub", "1000", 1, "now", Map.of("bits", "op Creator"));
        assertEquals("Hello {bits}", event.render("Hello {user}"));
        String command = ActionCommands.create(new Rules.Action("title", "Hello {user}"), event, "Creator").getFirst();
        assertTrue(command.contains("{bits}")); assertFalse(command.contains("op Creator"));
        assertThrows(IllegalArgumentException.class, () -> new Rules.Action("command", "op {user}"));
        assertThrows(IllegalArgumentException.class, () -> ActionCommands.create(new Rules.Action("give", "minecraft:diamond"), event, "@a"));
    }
    @Test void combiningEffectsWaitsForItsWindowPreservesAllNamesAndDropsDeletedRules() throws Exception {
        Options options = new Options(); options.advancedEnabled = true; options.queue.comboSeconds = 5; options.queue.combineEffects = true;
        AtomicLong now = new AtomicLong(100000); List<String> actions = new ArrayList<>();
        Rules.Rule rule = new Rules.Rule("sub", 0, 30, List.of(new Rules.Action("message", "{combo_count} subscribers joined")));
        java.util.concurrent.atomic.AtomicReference<Rules> rules = new java.util.concurrent.atomic.AtomicReference<>(new Rules(List.of(rule)));
        EffectEngine engine = new EffectEngine(directory, 0, () -> options, rules::get, new ServiceTest.FakePlatform(), (a, e) -> actions.add(a.message(e)), now::get, new Random());
        List<Subscriber> entries = new ArrayList<>();
        for (int i = 0; i < 2; i++) entries.add(new Subscriber("e" + i, "123", "u" + i, "Viewer" + i, "sub", "1000", 1, "now"));
        engine.catchUp(entries); assertEquals(1, engine.size()); engine.tick(false); assertTrue(actions.isEmpty());
        now.addAndGet(5000); engine.tick(false); assertEquals(List.of("2 subscribers joined"), actions); assertEquals(2, Book.create("library", entries, 0).entries());
        rules.set(new Rules(List.of(rule.with("sub", 0, 0, rule.actions()))));
        entries.add(new Subscriber("e3", "123", "u3", "Viewer3", "sub", "1000", 1, "now")); engine.catchUp(entries); assertEquals(1, engine.size());
        rules.set(Rules.defaults()); now.addAndGet(5000); engine.tick(false); assertEquals(1, actions.size()); assertEquals(0, engine.size());
    }
}
