package dev.subby.core;

public interface Platform {
    interface Audience { void message(String message); }
    record LecternTarget(String world, int x, int y, int z) {}
    void execute(Runnable action);
    void log(String message);
    void give(Audience audience, Book book, boolean live);
    LecternTarget targetedLectern(Audience audience);
    void refresh(Book book, java.util.List<LecternTarget> lecterns);
    void announce(String name);
    void action(Rules.Action action, Subscriber event);
}
