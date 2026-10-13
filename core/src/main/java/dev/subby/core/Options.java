package dev.subby.core;

import java.util.*;

public final class Options {
    public boolean advancedEnabled = false;
    public Set<String> events = new LinkedHashSet<>();
    public String targetPlayer = "";
    public BookStyle book = new BookStyle();
    public QueueSettings queue = new QueueSettings();
    public Safety safety = new Safety();
    public Moderation moderation = new Moderation();
    public Bot bot = new Bot();
    public static final class Bot {
        public boolean enabled = false;
        public String style = "silent";
        public String template = "Thank you, {user}! Your name is in the book.";
    }

    public static final class BookStyle {
        public String title = "Subscribers - Vol. {volume}";
        public String author = "Subby";
        public String line = "{display_name}";
        public String header = "Subscribers";
        public String footer = "";
        public String opening = "";
        public String prefix = "";
        public String suffix = "";
        public String sort = "chronological";
        public String layout = "compact";
        public String colour = "black";
        public String nameMode = "display";
        public String boundPlayer = "";
        public int namesPerPage = 4;
        public boolean clickableNames = false;
        public boolean romanVolumes = false;
        public boolean registryView = false;
        public boolean includeNew = true;
        public boolean includeGifts = true;
        public boolean includeResubs = true;
        public boolean tierSymbols = false;
        public boolean specialStyles = false;
        public boolean monthlyHeaders = false;
    }
    public static final class QueueSettings {
        public int capacity = 256;
        public int effectsPerTick = 4;
        public int intervalSeconds = 1;
        public int globalCooldownSeconds = 0;
        public int comboSeconds = 0;
        public boolean combineEffects = false;
        public boolean persistent = true;
    }
    public static final class Safety {
        public boolean hardcoreSafe = true;
        public boolean destructiveActions = false;
        public int minimumHealth = 0;
        public int itemsPerMinute = 64;
        public int mobsPerMinute = 5;
        public boolean skipDuringBoss = false;
        public Set<String> allowedDimensions = new LinkedHashSet<>();
        public Set<String> allowedCommands = new LinkedHashSet<>();
    }
    public static final class Moderation {
        public Set<String> blockedUsers = new LinkedHashSet<>();
        public Set<String> blockedWords = new LinkedHashSet<>();
        public Set<String> removedUsers = new LinkedHashSet<>();
        public Set<String> founders = new LinkedHashSet<>();
        public Set<String> bannedUsers = new LinkedHashSet<>();
        public Map<String, Long> banExpiries = new HashMap<>();
        public boolean minecraftFilter = false;
        public transient Map<String, String> filteredNames = Map.of();
        public boolean replaceRemoved = true;
        public boolean respectBans = false;
    }
    public Options copy() { Options copy = JsonFiles.GSON.fromJson(JsonFiles.GSON.toJson(this), Options.class); copy.moderation.filteredNames = moderation.filteredNames; return copy; }
    public void validate() {
        if (events == null || book == null || queue == null || safety == null || moderation == null || bot == null || targetPlayer == null)
            throw new IllegalArgumentException("Incomplete Subby options.");
        if (events.size() > 30 || events.stream().anyMatch(v -> !v.equals("bot") && !EventType.valid(v))) throw new IllegalArgumentException("Unsupported optional event.");
        if (!targetPlayer.isEmpty() && !targetPlayer.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Choose a Minecraft player name.");
        if (!Set.of("silent", "cute", "professional", "chaotic", "custom").contains(bot.style) || bot.template == null || bot.template.length() > 450) throw new IllegalArgumentException("Invalid bot style or template.");
        if (moderation.banExpiries == null || moderation.banExpiries.size() > 10000 || moderation.banExpiries.values().stream().anyMatch(v -> v == null || v < 0)) throw new IllegalArgumentException("Invalid ban expiration list.");
        if (safety.allowedDimensions.stream().anyMatch(v -> !v.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))) throw new IllegalArgumentException("Choose fixed dimension IDs.");
        for (Set<String> list : List.of(safety.allowedDimensions, safety.allowedCommands, moderation.blockedUsers, moderation.blockedWords, moderation.removedUsers, moderation.founders, moderation.bannedUsers))
            if (list.size() > 10000 || list.stream().anyMatch(v -> v == null || v.length() > 1000)) throw new IllegalArgumentException("A moderation or safety list is too large.");
        for (String value : List.of(book.title, book.author, book.line, book.header, book.footer, book.opening, book.prefix, book.suffix))
            if (value == null || value.length() > 1000) throw new IllegalArgumentException("Book text must be shorter than 1000 characters.");
        if (book.author.length() > 32 || book.namesPerPage < 1 || book.namesPerPage > 8)
            throw new IllegalArgumentException("Choose 1–8 names per page and an author shorter than 33 characters.");
        if (book.boundPlayer == null || !book.boundPlayer.isEmpty() && !book.boundPlayer.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("Choose a Minecraft book recipient.");
        if (!Set.of("chronological", "alphabetical").contains(book.sort) || !Set.of("compact", "paragraph", "page").contains(book.layout)
                || !Set.of("display", "login").contains(book.nameMode)) throw new IllegalArgumentException("Invalid book layout.");
        if (!Set.of("black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white").contains(book.colour)) throw new IllegalArgumentException("Choose a Minecraft text colour.");
        if (queue.capacity < 1 || queue.capacity > 10000 || queue.effectsPerTick < 1 || queue.effectsPerTick > 100
                || queue.intervalSeconds < 1 || queue.intervalSeconds > 60 || queue.comboSeconds < 0 || queue.comboSeconds > 60
                || queue.globalCooldownSeconds < 0 || queue.globalCooldownSeconds > 3600)
            throw new IllegalArgumentException("Queue settings are outside their allowed range.");
        if (safety.minimumHealth < 0 || safety.minimumHealth > 20 || safety.itemsPerMinute < 0 || safety.itemsPerMinute > 4096
                || safety.mobsPerMinute < 0 || safety.mobsPerMinute > 100) throw new IllegalArgumentException("Invalid safety limits.");
    }
}
