package dev.subby.core;

import java.util.*;
import java.util.function.UnaryOperator;

public record Rules(List<Rule> rules) {
    public Rules { rules = List.copyOf(Objects.requireNonNull(rules)); if (rules.size() > 100) throw new IllegalArgumentException("At most 100 rules"); }
    public record Action(String type, String value, Map<String, String> parameters, int weight) {
        public static final List<String> TYPES = List.of("message", "title", "subtitle", "actionbar", "sound", "particle", "give", "effect", "mob", "score", "advancement", "function", "command", "book", "chest", "sign", "display", "redstone", "token");
        public Action(String type, String value) { this(type, value, Map.of(), 1); }
        public Action {
            if (!TYPES.contains(type) || value == null || value.length() > 1000) throw new IllegalArgumentException("Choose a supported action and a value shorter than 1001 characters.");
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
            if (parameters.size() > 10 || parameters.values().stream().anyMatch(v -> v.length() > 100)) throw new IllegalArgumentException("Too many action settings.");
            if (weight == 0) weight = 1;
            if (weight < 1 || weight > 1000) throw new IllegalArgumentException("Choose a weight between 1 and 1000.");
            if (Set.of("sound", "particle", "give", "effect", "mob", "advancement", "function").contains(type) && !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Choose a fixed Minecraft resource ID, for example minecraft:diamond.");
            if (type.equals("score") && !value.matches("[A-Za-z0-9_.+-]{1,16}")) throw new IllegalArgumentException("Choose a scoreboard objective name (up to 16 characters).");
            if (Set.of("chest", "sign", "display", "redstone").contains(type) && !value.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Choose the name of a physical binding.");
            if (parameters.containsKey("item") && !parameters.get("item").matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Choose a fixed item ID.");
            if (type.equals("command") && (value.isBlank() || value.contains("\n") || value.contains("\r") || value.startsWith("/") || value.contains("{") || value.contains("}")))
                throw new IllegalArgumentException("Use one fixed command, without a leading slash or placeholders.");
            integer(parameters, "amount", 1, 1, 4096);
            integer(parameters, "seconds", 10, 1, 3600);
            integer(parameters, "level", 0, 0, 10);
        }
        public int number(String key, int fallback, int min, int max) { return integer(parameters, key, fallback, min, max); }
        private static int integer(Map<String, String> values, String key, int fallback, int min, int max) {
            try { int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback))); if (value >= min && value <= max) return value; }
            catch (NumberFormatException ignored) {}
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ".");
        }
        public Action parameter(String key, String value) { Map<String, String> copy = new HashMap<>(parameters); copy.put(key, value); return new Action(type, this.value, copy, weight); }
        public String message(Subscriber event) { return event.render(value); }
    }
    public record Conditions(String id, boolean enabled, int minimumTier, int minimumAmount, String rewardId, int priority, boolean randomPool, int minimumCombo) {
        public Conditions(String id, boolean enabled, int minimumTier, int minimumAmount, String rewardId, int priority, boolean randomPool) { this(id, enabled, minimumTier, minimumAmount, rewardId, priority, randomPool, 0); }
        public Conditions {
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            if (rewardId == null) rewardId = "";
            if (minimumTier < 0 || minimumTier > 3 || minimumAmount < 0 || minimumAmount > 1000000 || priority < -100 || priority > 100 || rewardId.length() > 100 || minimumCombo < 0 || minimumCombo > 10000)
                throw new IllegalArgumentException("Condition is outside its allowed range.");
        }
        public static Conditions defaults() { return new Conditions(UUID.randomUUID().toString(), true, 0, 0, "", 0, false); }
    }
    public record Rule(String event, int minimumMonths, int cooldownSeconds, List<Action> actions, Conditions conditions) {
        public Rule(String event, int minimumMonths, int cooldownSeconds, List<Action> actions) { this(event, minimumMonths, cooldownSeconds, actions, Conditions.defaults()); }
        public Rule {
            if (!EventType.valid(event) || minimumMonths < 0 || minimumMonths > 10000 || cooldownSeconds < 0 || cooldownSeconds > 86400) throw new IllegalArgumentException("Invalid event, minimum months, or cooldown.");
            actions = List.copyOf(Objects.requireNonNull(actions));
            if (actions.size() > 10) throw new IllegalArgumentException("At most 10 actions per rule");
            if (conditions == null) conditions = Conditions.defaults();
        }
        public Rule with(String event, int months, int cooldown, List<Action> actions) { return new Rule(event, months, cooldown, actions, conditions); }
        public Rule condition(UnaryOperator<Conditions> edit) { return new Rule(event, minimumMonths, cooldownSeconds, actions, edit.apply(conditions)); }
        public boolean matches(Subscriber entry) {
            int tier; try { tier = Integer.parseInt(entry.tier()) / 1000; } catch (NumberFormatException e) { tier = 0; }
            int amount = switch (entry.kind()) { case "bits" -> entry.amount("bits"); case "raid" -> entry.amount("raid_viewers"); case "gift_batch" -> entry.amount("gift_count"); case "hype_begin", "hype_progress", "hype_end" -> entry.amount("hype_level"); default -> entry.months(); };
            return conditions.enabled && event.equals(entry.kind()) && entry.months() >= minimumMonths && tier >= conditions.minimumTier && amount >= conditions.minimumAmount && entry.amount("combo_count") >= conditions.minimumCombo
                    && (conditions.rewardId.isBlank() || conditions.rewardId.equals(entry.detail("reward_id")));
        }
    }
    public static Rules defaults() { return new Rules(List.of()); }
}
