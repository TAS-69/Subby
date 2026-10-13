package dev.subby.core;

import java.util.*;

public record Binding(String name, String kind, String world, int x, int y, int z, String mode) {
    public Binding {
        if (name == null || !name.matches("[a-z0-9_-]{1,32}") || !Set.of("archive", "live_chest", "chest", "sign", "display", "redstone").contains(kind)
                || world == null || world.length() > 200 || !Set.of("latest", "recent", "recent_events", "wall", "top_gifters", "top_bits", "manual").contains(mode)) throw new IllegalArgumentException("Invalid physical binding.");
    }
    public String board(List<Subscriber> entries, List<SupporterRegistry.Supporter> supporters) {
        return board(entries, supporters, 0);
    }
    public String board(List<Subscriber> entries, List<SupporterRegistry.Supporter> supporters, int wallPage) {
        if (mode.equals("wall")) return supporters.stream().filter(s -> !s.firstSubscription().isBlank() || s.bits() > 0 || s.giftsGiven() > 0).skip(wallPage * 4L).limit(4).map(SupporterRegistry.Supporter::name).collect(java.util.stream.Collectors.joining("\n"));
        if (mode.equals("recent_events")) {
            List<Subscriber> recent = new ArrayList<>(entries.stream().filter(e -> !e.kind().equals("control")).toList()); Collections.reverse(recent);
            return "Recent events\n" + recent.stream().limit(3).map(e -> e.kind().replace('_', ' ') + ": " + e.name()).collect(java.util.stream.Collectors.joining("\n"));
        }
        if (mode.equals("top_gifters") || mode.equals("top_bits")) {
            boolean gifts = mode.equals("top_gifters");
            return (gifts ? "Top gifters" : "Top Bits") + "\n" + supporters.stream().filter(s -> (gifts ? s.giftsGiven() : s.bits()) > 0)
                    .sorted(Comparator.comparingLong((SupporterRegistry.Supporter s) -> gifts ? s.giftsGiven() : s.bits()).reversed()).limit(3)
                    .map(s -> s.name() + " " + (gifts ? s.giftsGiven() : s.bits())).collect(java.util.stream.Collectors.joining("\n"));
        }
        List<Subscriber> subscriptions = entries.stream().filter(Subscriber::subscription).toList();
        int count = mode.equals("latest") ? 1 : 3;
        List<Subscriber> recent = new ArrayList<>(subscriptions.subList(Math.max(0, subscriptions.size() - count), subscriptions.size())); Collections.reverse(recent);
        return (count == 1 ? "Latest subscriber" : "Recent subscribers") + "\n" + recent.stream().map(Subscriber::name).collect(java.util.stream.Collectors.joining("\n"));
    }
}
