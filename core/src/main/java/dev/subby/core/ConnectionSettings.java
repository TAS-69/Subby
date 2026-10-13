package dev.subby.core;

import java.net.URI;
import java.util.Set;

public record ConnectionSettings(String mode, String serviceUrl, String label, boolean autoConnect) {
    public ConnectionSettings {
        if (!Set.of("companion", "direct").contains(mode)) throw new IllegalArgumentException("Choose Companion or direct Twitch mode.");
        URI uri = URI.create(serviceUrl);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                || !(uri.getScheme().equals("https") || uri.getScheme().equals("http") && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())))
            throw new IllegalArgumentException("Use an HTTPS Companion address, or HTTP localhost for a local service.");
        serviceUrl = serviceUrl.replaceAll("/+$", "");
        if (label == null || label.isBlank() || label.length() > 64) throw new IllegalArgumentException("Choose an installation name shorter than 65 characters.");
    }
    public static ConnectionSettings defaults() { return new ConnectionSettings("companion", "http://localhost:3030", "Minecraft", true); }
}
