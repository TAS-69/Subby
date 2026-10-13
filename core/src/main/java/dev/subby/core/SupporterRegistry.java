package dev.subby.core;

import java.util.*;

public final class SupporterRegistry {
    public record Supporter(String userId, String name, String login, String firstSeen, String lastSeen,
                            int months, String tier, long events, long giftsReceived, long giftsGiven, long bits,
                            boolean moderator, boolean vip, String firstSubscription, String lastEvent) {
        public String summary() { return name + " | " + userId + " | first seen " + firstSeen + " | first recorded sub " + (firstSubscription.isBlank() ? "none" : firstSubscription) + " | " + months + " known months | tier " + tier.replace("000", "") + " | gifts received " + giftsReceived + " | gifts given " + giftsGiven + " | Bits " + bits + " | last event " + lastEvent + (moderator ? " | Moderator" : "") + (vip ? " | VIP" : ""); }
    }
    private final Map<String, Supporter> supporters = new LinkedHashMap<>();
    public synchronized void record(Subscriber event) {
        if (event.userId().isBlank() || Set.of("control", "entry").contains(event.kind())) return;
        Supporter old = supporters.get(event.userId());
        if (old == null) old = new Supporter(event.userId(), event.name(), event.detail("login"), event.receivedAt(), event.receivedAt(), 0, "1000", 0, 0, 0, 0, false, false, "", "");
        supporters.put(event.userId(), new Supporter(event.userId(), event.name(), event.detail("login").isBlank() ? old.login : event.detail("login"), old.firstSeen, event.receivedAt(),
                event.subscription() ? Math.max(old.months, event.months()) : old.months, event.subscription() ? event.tier() : old.tier,
                old.events + 1, old.giftsReceived + (event.kind().equals("gift") ? 1 : 0), old.giftsGiven + (event.kind().equals("gift_batch") ? event.amount("gift_count") : 0),
                old.bits + (event.kind().equals("bits") ? event.amount("bits") : 0),
                event.kind().equals("moderator_add") || !event.kind().equals("moderator_remove") && old.moderator,
                event.kind().equals("vip_add") || !event.kind().equals("vip_remove") && old.vip,
                old.firstSubscription.isBlank() && event.subscription() ? event.detail("recovered").equals("true") ? "unknown (recovered)" : event.receivedAt() : old.firstSubscription, event.kind()));
    }
    public synchronized List<Supporter> all() { return List.copyOf(supporters.values()); }
    public synchronized Optional<Supporter> find(String text) { return supporters.values().stream().filter(s -> s.userId.equals(text) || s.name.equalsIgnoreCase(text) || s.login.equalsIgnoreCase(text)).findFirst(); }
}
