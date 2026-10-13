package dev.subby.core;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class SubbyEvents {
    public record SavedEvent(String libraryId, Subscriber event) {}
    private static final CopyOnWriteArrayList<Consumer<SavedEvent>> listeners = new CopyOnWriteArrayList<>();
    private SubbyEvents() {}
    public static AutoCloseable register(Consumer<SavedEvent> listener) { listeners.add(java.util.Objects.requireNonNull(listener)); return () -> listeners.remove(listener); }
    static void publish(String library, Subscriber event, Consumer<String> log) {
        SavedEvent saved = new SavedEvent(library, event);
        for (Consumer<SavedEvent> listener : listeners) try { listener.accept(saved); } catch (RuntimeException e) { log.accept("A Subby event listener failed. Saved names are unaffected."); }
    }
}
