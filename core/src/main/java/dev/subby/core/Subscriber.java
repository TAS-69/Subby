package dev.subby.core;

import com.google.gson.JsonObject;
import java.time.Instant;

public record Subscriber(String messageId, String broadcasterId, String userId, String name,
                         String kind, String tier, int months, String receivedAt) {
    public Subscriber {
        if (messageId == null || messageId.isBlank() || broadcasterId == null || userId == null ||
            name == null || name.isBlank() || kind == null || tier == null || receivedAt == null)
            throw new IllegalArgumentException("Incomplete subscriber entry");
        name = safeName(name);
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
        return new Subscriber(metadata.get("message_id").getAsString(), broadcasterId,
                event.get("user_id").getAsString(), name, kind, event.get("tier").getAsString(),
                resub ? event.get("cumulative_months").getAsInt() : 1, Instant.now().toString());
    }

    public static String safeName(String name) {
        StringBuilder result = new StringBuilder();
        name.codePoints().filter(c -> !Character.isISOControl(c) && c != 0xA7 && Character.getType(c) != Character.FORMAT)
                .limit(25).forEach(result::appendCodePoint);
        return result.toString().isBlank() ? "Subscriber" : result.toString();
    }
}
