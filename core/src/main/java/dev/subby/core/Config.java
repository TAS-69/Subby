package dev.subby.core;

public record Config(String clientId, boolean autoConnect, boolean includeResubscriptions, boolean announceSubscribers) {
    public Config {
        if (clientId == null || !clientId.matches("[a-zA-Z0-9]{0,128}"))
            throw new IllegalArgumentException("clientId must be the public Twitch application's client ID.");
    }
    public static Config defaults() { return new Config("", true, true, false); }
    public Config withClientId(String id) { return new Config(id, autoConnect, includeResubscriptions, announceSubscribers); }
}
