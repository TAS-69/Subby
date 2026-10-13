package dev.subby.core;

public interface Platform {
    interface Audience {
        void message(String message);
        default String id() { return "console"; }
        default String name() { return "Server"; }
        default boolean player() { return false; }
        default boolean permitted(String permission) { return true; }
        default Audience unwrap() { return this; }
        default void link(String label, String url) { message(label + ": " + url); }
    }
    record LecternTarget(String world, int x, int y, int z) {}
    record PlayerState(String name, String dimension, double health, boolean hardcore, boolean boss) {}
    void execute(Runnable action);
    void log(String message);
    void give(Audience audience, Book book, boolean live);
    LecternTarget targetedLectern(Audience audience);
    void refresh(Book book, java.util.List<LecternTarget> lecterns);
    default void refresh(Book book, java.util.List<LecternTarget> lecterns, String player) { refresh(book, lecterns); }
    void announce(String name);
    void action(Rules.Action action, Subscriber event);
    default PlayerState playerState(String name) { return null; }
    default java.util.List<String> players() { return java.util.List.of(); }
    default void action(Rules.Action action, Subscriber event, String target) { action(action, event); }
    default void openMenu(Audience audience, UiMenu menu) { audience.message("Use /subby help from the console."); }
    default void closeMenu(Audience audience) {}
    default void profiles(Audience audience) { audience.message("Profiles are unavailable in this environment."); }
    default Binding targetedBinding(Audience audience, String name, String kind, String mode) { throw new IllegalArgumentException("This environment does not support physical bindings."); }
    default boolean depositArchive(Binding binding, Book book) { return false; }
    default boolean depositItem(Binding binding, String item, int amount) { return false; }
    default void board(Binding binding, String text, String library) {}
    default boolean pulse(Binding binding, boolean powered) { return false; }
    default void giveNamed(String name, Book book) { throw new IllegalArgumentException("Player is not available."); }
    default void giveNamed(String name, Book book, boolean live) { giveNamed(name, book); }
    default boolean liveChest(Binding binding, Book book) { return false; }
    default boolean nameFilterAvailable(String player) { return false; }
    default java.util.concurrent.CompletableFuture<String> filterName(String name, String player) { return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("No configured name filter is available.")); }
}
