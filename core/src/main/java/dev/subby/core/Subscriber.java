package dev.subby.core;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

public record Subscriber(String messageId, String broadcasterId, String userId, String name,
                         String kind, String tier, int months, String receivedAt, Map<String, String> details) {
    public Subscriber {
        if (messageId == null || messageId.isBlank() || broadcasterId == null || userId == null ||
            name == null || name.isBlank() || kind == null || tier == null || receivedAt == null)
            throw new IllegalArgumentException("Incomplete subscriber entry");
        if (messageId.length() > 256 || broadcasterId.length() > 64 || userId.length() > 64 || kind.length() > 64 || tier.length() > 16 || receivedAt.length() > 128 || months < 0 || months > 10000) throw new IllegalArgumentException("Subscriber fields exceed allowed limits");
        name = safeName(name);
        details = details == null ? Map.of() : Map.copyOf(details);
        if (details.size() > 32 || details.entrySet().stream().anyMatch(e -> !e.getKey().matches("[a-z_]{1,64}") || e.getValue().length() > 1000)) throw new IllegalArgumentException("Event details exceed allowed limits");
    }
    public Subscriber(String messageId, String broadcasterId, String userId, String name, String kind, String tier, int months, String receivedAt) {
        this(messageId, broadcasterId, userId, name, kind, tier, months, receivedAt, Map.of());
    }
    public boolean subscription() { return Set.of("sub", "resub", "gift", "sync").contains(kind); }
    public boolean bookEntry() { return subscription() || kind.equals("entry"); }
    public String detail(String key) { return details.getOrDefault(key, ""); }
    public int amount(String key) { try { return Integer.parseInt(detail(key)); } catch (NumberFormatException e) { return 0; } }
    public Subscriber withDetail(String key, String value) {
        Map<String, String> replacement = new HashMap<>(details); replacement.put(key, value);
        return new Subscriber(messageId, broadcasterId, userId, name, kind, tier, months, receivedAt, replacement);
    }
    public String render(String template) { return render(template, "display"); }
    public String render(String template, String nameMode) {
        Map<String, String> values = new HashMap<>(details);
        values.put("display_name", nameMode.equals("login") && !detail("login").isEmpty() ? detail("login") : name);
        values.put("user", nameMode.equals("login") && !detail("login").isEmpty() ? detail("login") : name);
        values.put("login", detail("login").isEmpty() ? name : detail("login"));
        if (nameMode.equals("login") && !detail("gifter_login").isBlank()) values.put("gifter", detail("gifter_login"));
        values.put("months", months == 0 && detail("recovered").equals("true") ? "unknown" : Integer.toString(months));
        values.put("tier", tier.replace("000", ""));
        values.put("kind", kind);
        boolean recovered = detail("recovered").equals("true") && detail("timestamp_known").equals("false");
        values.put("timestamp", recovered ? "unknown" : receivedAt);
        values.put("date", recovered ? "unknown" : receivedAt.length() >= 10 ? receivedAt.substring(0, 10) : receivedAt);
        values.putIfAbsent("gift_count", kind.equals("gift") ? "1" : "0");
        for (String key : List.of("streak", "bits", "raid_viewers", "hype_level", "hype_total", "hype_progress", "hype_goal")) values.putIfAbsent(key, "0");
        values.putIfAbsent("combo_count", "1");
        for (String key : List.of("gifter", "reward", "poll", "prediction", "outcome")) values.putIfAbsent(key, "");
        Matcher matcher = Pattern.compile("\\{([a-z_]+)\\}").matcher(template);
        return matcher.replaceAll(match -> Matcher.quoteReplacement(values.getOrDefault(match.group(1), match.group())));
    }

    public static Subscriber notification(JsonObject root, String broadcasterId, boolean includeResubs) {
        JsonObject metadata = root.getAsJsonObject("metadata");
        if (!"notification".equals(metadata.get("message_type").getAsString())) return null;
        String type = metadata.get("subscription_type").getAsString();
        boolean resub = type.equals("channel.subscription.message");
        if (!type.equals("channel.subscribe") && !(resub && includeResubs)) return null;
        JsonObject event = root.getAsJsonObject("payload").getAsJsonObject("event");
        if (!broadcasterId.equals(event.get("broadcaster_user_id").getAsString())) return null;
        String name = event.get("user_name").getAsString();
        if (name.isBlank()) name = event.get("user_login").getAsString();
        String kind = resub ? "resub" : event.get("is_gift").getAsBoolean() ? "gift" : "sub";
        Map<String, String> details = new HashMap<>();
        details.put("login", event.get("user_login").getAsString());
        if (event.has("streak_months") && !event.get("streak_months").isJsonNull()) details.put("streak", event.get("streak_months").getAsString());
        return new Subscriber(metadata.get("message_id").getAsString(), broadcasterId,
                event.get("user_id").getAsString(), name, kind, event.get("tier").getAsString(),
                resub ? event.get("cumulative_months").getAsInt() : 1, Instant.now().toString(), details);
    }

    public static String safeName(String name) {
        StringBuilder result = new StringBuilder();
        name.codePoints().filter(c -> !Character.isISOControl(c) && c != 0xA7 && Character.getType(c) != Character.FORMAT)
                .limit(25).forEach(result::appendCodePoint);
        return result.toString().isBlank() ? "Subscriber" : result.toString();
    }
}
