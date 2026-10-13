package dev.subby.core;

import java.util.*;

public final class ActionCommands {
    private ActionCommands() {}
    public static List<String> create(Rules.Action action, Subscriber event, String target) {
        if (!Set.of("message", "function", "command", "book").contains(action.type()) && !target.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Choose an online Minecraft player in Advanced > Effect player.");
        String text = JsonFiles.GSON.toJson(Map.of("text", action.message(event)));
        int amount = action.number("amount", 1, 1, 4096);
        return switch (action.type()) {
            case "title", "subtitle", "actionbar" -> List.of("title " + target + " " + action.type() + " " + text);
            case "sound" -> List.of("execute at " + target + " run playsound " + action.value() + " master " + target + " ~ ~ ~ 0.6 1");
            case "particle" -> List.of("execute at " + target + " run particle " + action.value() + " ~ ~1 ~ 0.5 0.5 0.5 0 " + Math.min(100, amount) + " force " + target);
            case "give" -> List.of("give " + target + " " + action.value() + " " + Math.min(64, amount));
            case "effect" -> List.of("effect give " + target + " " + action.value() + " " + action.number("seconds", 10, 1, 3600) + " " + action.number("level", 0, 0, 10));
            case "mob" -> List.of("execute at " + target + " run summon " + action.value() + " ~ ~ ~");
            case "score" -> List.of("scoreboard players add " + target + " " + action.value() + " " + amount);
            case "advancement" -> List.of("advancement grant " + target + " only " + action.value());
            case "command" -> List.of(action.value());
            case "function" -> List.of("data modify storage subby:event current set value " + JsonFiles.GSON.toJson(event), "function " + action.value());
            default -> List.of();
        };
    }
    public static boolean dangerous(Rules.Action action) {
        return Set.of("command", "function", "mob", "redstone").contains(action.type()) || action.type().equals("effect") && !Set.of("minecraft:speed", "minecraft:haste", "minecraft:strength", "minecraft:instant_health", "minecraft:jump_boost", "minecraft:regeneration", "minecraft:resistance", "minecraft:fire_resistance", "minecraft:water_breathing", "minecraft:invisibility", "minecraft:night_vision", "minecraft:health_boost", "minecraft:absorption", "minecraft:saturation", "minecraft:luck", "minecraft:slow_falling", "minecraft:conduit_power", "minecraft:dolphins_grace", "minecraft:hero_of_the_village").contains(action.value());
    }
}
