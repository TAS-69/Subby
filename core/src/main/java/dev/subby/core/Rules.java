package dev.subby.core;

import java.util.*;

public record Rules(List<Rule> rules) {
    public Rules { rules = List.copyOf(Objects.requireNonNull(rules)); if (rules.size() > 100) throw new IllegalArgumentException("At most 100 rules"); }
    public record Action(String type, String value) {
        public Action {
            if (type == null || value == null) throw new IllegalArgumentException("Action needs a type and value");
            switch (type) {
                case "message" -> { if (value.length() > 500) throw new IllegalArgumentException("Message too long"); }
                case "sound", "function" -> {
                    if (!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("Use a fixed namespaced sound/function ID; placeholders and commands are not allowed here.");
                }
                default -> throw new IllegalArgumentException("Action must be message, sound, or function");
            }
        }
        public String message(Subscriber event) {
            return value.replace("{user}", event.name()).replace("{display_name}", event.name())
                    .replace("{months}", Integer.toString(event.months())).replace("{tier}", event.tier().replace("000", ""))
                    .replace("{kind}", event.kind()).replace("{timestamp}", event.receivedAt());
        }
    }
    public record Rule(String event, int minimumMonths, int cooldownSeconds, List<Action> actions) {
        public Rule {
            if (!Set.of("sub", "resub", "gift", "demo").contains(event) || minimumMonths < 0 || cooldownSeconds < 0 || cooldownSeconds > 86400)
                throw new IllegalArgumentException("Invalid event, minimumMonths, or cooldownSeconds");
            actions = List.copyOf(Objects.requireNonNull(actions));
            if (actions.size() > 10) throw new IllegalArgumentException("At most 10 actions per rule");
        }
    }
    public static Rules defaults() {
        return new Rules(List.of());
    }
}
