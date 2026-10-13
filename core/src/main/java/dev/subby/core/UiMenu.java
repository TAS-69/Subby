package dev.subby.core;

import java.util.*;

public record UiMenu(String title, int rows, List<Button> buttons) {
    public record Button(int slot, String icon, String label, List<String> description, Runnable action) {
        public Button {
            description = List.copyOf(description);
            Objects.requireNonNull(icon);
            Objects.requireNonNull(label);
            Objects.requireNonNull(action);
        }
    }
    public UiMenu {
        if (rows < 1 || rows > 6) throw new IllegalArgumentException("Menus need between one and six rows.");
        buttons = List.copyOf(buttons);
        Set<Integer> used = new HashSet<>();
        for (Button button : buttons) if (button.slot() < 0 || button.slot() >= rows * 9 || !used.add(button.slot()))
            throw new IllegalArgumentException("Invalid or duplicate menu slot.");
    }
    public void click(int slot) {
        buttons.stream().filter(b -> b.slot() == slot).findFirst().ifPresent(button -> button.action().run());
    }
    public static Button button(int slot, String icon, String label, Runnable action, String... description) {
        return new Button(slot, icon, label, Arrays.asList(description), action);
    }
}
