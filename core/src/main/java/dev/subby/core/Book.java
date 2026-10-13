package dev.subby.core;

import java.util.*;

public record Book(String libraryId, int volume, String title, String author, List<String> pages, int entries) {
    public static final int NAMES_PER_PAGE = 4;
    public static final int PAGES_PER_VOLUME = 100;
    public static final int NAMES_PER_VOLUME = NAMES_PER_PAGE * PAGES_PER_VOLUME;

    public Book { pages = List.copyOf(pages); }

    public static Book create(String id, List<Subscriber> entries, int requestedVolume) {
        int count = Math.max(1, (entries.size() + NAMES_PER_VOLUME - 1) / NAMES_PER_VOLUME);
        int volume = requestedVolume == 0 ? count : requestedVolume;
        if (volume < 1 || volume > count) throw new IllegalArgumentException("Choose a volume between 1 and " + count + ".");
        int start = (volume - 1) * NAMES_PER_VOLUME;
        int end = Math.min(entries.size(), start + NAMES_PER_VOLUME);
        List<String> pages = new ArrayList<>();
        for (int offset = start; offset < end; offset += NAMES_PER_PAGE) {
            StringBuilder page = new StringBuilder("Subscribers\n\n");
            for (int i = offset; i < Math.min(end, offset + NAMES_PER_PAGE); i++) {

                String name = entries.get(i).name();
                int[] chars = name.codePoints().toArray();
                for (int j = 0; j < chars.length; j += 10) {
                    page.append(new String(chars, j, Math.min(10, chars.length - j))).append('\n');
                }
            }
            pages.add(page.toString().stripTrailing());
        }
        if (pages.isEmpty()) pages.add("Subby\n\nYour subscribers will appear here.\n\nThank you for the support!");
        return new Book(id, volume, "Subby - Vol. " + volume, "Subby", pages, entries.size());
    }
}
