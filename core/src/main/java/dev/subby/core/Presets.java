package dev.subby.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class Presets {
    public record Preset(int schema, Options options, Rules rules) {
        public Preset { if (schema != 1 || options == null || rules == null) throw new IllegalArgumentException("Unsupported preset format."); options.validate(); }
    }
    private final Path directory;
    public Presets(Path settings) { directory = settings.resolve("presets"); }
    private Path path(String name) {
        if (!name.matches("[a-zA-Z0-9_-]{1,48}")) throw new IllegalArgumentException("Use a preset name of 1–48 letters, numbers, underscores, or dashes.");
        return directory.resolve(name + ".json");
    }
    public void export(String name, Options options, Rules rules) throws IOException {
        Options copy = options.copy(); copy.targetPlayer = ""; copy.book.boundPlayer = ""; copy.safety.allowedCommands.clear();
        copy.moderation.blockedUsers.clear(); copy.moderation.removedUsers.clear();
        copy.moderation.founders.clear(); copy.moderation.bannedUsers.clear(); copy.moderation.banExpiries.clear(); copy.moderation.minecraftFilter = false; copy.bot.enabled = false;
        JsonFiles.write(path(name), new Preset(1, copy, rules));
    }
    public Preset read(String name) throws IOException {
        Path path = path(name);
        if (!Files.exists(path) || Files.size(path) > 1048576) throw new IllegalArgumentException("Preset is missing or larger than 1 MB.");
        Preset source = JsonFiles.read(path, Preset.class, null); Options safe = source.options.copy();
        safe.advancedEnabled = false; safe.targetPlayer = ""; safe.safety.allowedCommands.clear(); safe.safety.destructiveActions = false; safe.safety.hardcoreSafe = true; safe.moderation.minecraftFilter = false;
        safe.book.boundPlayer = "";
        return new Preset(1, safe, source.rules);
    }
    public static Preset builtIn(String name) {
        Options options = new Options(); List<Rules.Rule> rules = new ArrayList<>();
        switch (name) {
            case "book-only" -> {}
            case "friendly" -> rules.add(new Rules.Rule("sub", 0, 2, List.of(new Rules.Action("message", "Thank you, {user}! Your name is in the subscriber book."), new Rules.Action("sound", "minecraft:block.note_block.bell"))));
            case "hardcore" -> { options.safety.minimumHealth = 16; options.safety.skipDuringBoss = true; rules.add(new Rules.Rule("sub", 0, 5, List.of(new Rules.Action("message", "Welcome to the book, {user}!")))); }
            default -> throw new IllegalArgumentException("Choose book-only, friendly, or hardcore.");
        }
        return new Preset(1, options, new Rules(rules));
    }
}
