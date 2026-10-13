package dev.subby.core;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public record Book(String libraryId, int volume, String title, String author, List<String> pages, int entries,
                   List<List<Span>> richPages) {
    public static final int NAMES_PER_PAGE = 4;
    public static final int PAGES_PER_VOLUME = 100;
    public static final int NAMES_PER_VOLUME = NAMES_PER_PAGE * PAGES_PER_VOLUME;
    public record Span(String text, String colour, String url) {}
    public Book {
        pages = List.copyOf(pages);
        richPages = richPages == null ? pages.stream().map(page -> List.of(new Span(page, "black", ""))).toList()
                : richPages.stream().map(List::copyOf).toList();
    }
    public Book(String libraryId, int volume, String title, String author, List<String> pages, int entries) {
        this(libraryId, volume, title, author, pages, entries, null);
    }
    public static Book keepsake(String id, String name, String text) {
        List<String> lines = wrap(text); List<String> pages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i += 14) pages.add(String.join("\n", lines.subList(i, Math.min(i + 14, lines.size()))));
        if (pages.isEmpty()) pages.add(name);
        return new Book(id, 1, truncate("Supporter: " + name, 32), "Subby", pages, 1);
    }
    public Book preview() { return new Book(libraryId, volume, truncate("Preview - " + title, 32), author, pages, entries, richPages); }
    public static Book create(String id, List<Subscriber> entries, int requestedVolume) {
        Options options = new Options();
        options.book.title = "Subby - Vol. {volume}";
        return create(id, entries, requestedVolume, options);
    }
    public static Book create(String id, List<Subscriber> source, int requestedVolume, Options options) {
        Options.BookStyle style = options.book;
        List<Subscriber> entries = new ArrayList<>(source.stream().filter(Subscriber::bookEntry)
                .filter(e -> !e.kind().equals("sub") || style.includeNew)
                .filter(e -> !e.kind().equals("gift") || style.includeGifts)
                .filter(e -> !e.kind().equals("resub") || style.includeResubs)
                .map(e -> moderate(e, options)).filter(Objects::nonNull).toList());
        if (style.registryView) {
            Map<String, Subscriber> latest = new LinkedHashMap<>();
            for (Subscriber entry : entries) {
                String key = entry.userId().isBlank() ? entry.name().toLowerCase(Locale.ROOT) : entry.userId();
                Subscriber previous = latest.get(key);
                latest.put(key, previous == null ? entry : new Subscriber(entry.messageId(), entry.broadcasterId(), entry.userId(), entry.name(), entry.kind(), entry.tier(), Math.max(previous.months(), entry.months()), entry.receivedAt(), entry.details()));
            }
            entries = new ArrayList<>(latest.values());
        }
        if (style.sort.equals("alphabetical")) entries.sort(Comparator.comparing(e -> display(e, style), String.CASE_INSENSITIVE_ORDER));
        List<List<Span>> all = new ArrayList<>();
        if (!style.opening.isBlank()) {
            List<String> lines = wrap(style.opening);
            for (int i = 0; i < lines.size(); i += 14) all.add(List.of(new Span(String.join("\n", lines.subList(i, Math.min(i + 14, lines.size()))), "black", "")));
        }
        List<Span> page = null;
        int linesUsed = 0, names = 0;
        String month = "";
        int perPage = style.layout.equals("page") ? 1 : style.namesPerPage;
        List<String> footer = wrap(style.footer);
        if (footer.size() > 2) footer = footer.subList(0, 2);
        for (Subscriber entry : entries) {
            boolean removed = contains(options.moderation.removedUsers, entry);
            if (removed && !options.moderation.replaceRemoved) continue;
            String entryMonth = style.monthlyHeaders && entry.receivedAt().length() >= 7 ? entry.receivedAt().substring(0, 7) : "";
            String monthTitle = entryMonth;
            try { if (!entryMonth.isEmpty()) monthTitle = java.time.YearMonth.parse(entryMonth).format(java.time.format.DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH)); } catch (java.time.format.DateTimeParseException ignored) {}
            String headerText = style.header + (entryMonth.isEmpty() ? "" : "\n" + monthTitle);
            List<String> header = wrap(headerText);
            if (header.size() > 2) header = header.subList(0, 2);
            String template = entry.kind().equals("entry") && !entry.detail("book_template").isEmpty() ? entry.detail("book_template") : style.line;
            String name = removed ? "[Removed]" : style.prefix + truncate(entry.render(template, style.nameMode), 1000) + style.suffix;
            if (style.tierSymbols) name = switch (entry.tier()) { case "2000" -> "◆ "; case "3000" -> "♥ "; default -> "★ "; } + name;
            if (style.specialStyles && entry.kind().equals("gift")) name = "Gift: " + name;
            if (style.specialStyles && contains(options.moderation.founders, entry)) name = "Founder: " + name;
            if (style.specialStyles && entry.amount("streak") >= 12) name += " · " + entry.amount("streak") + " month streak";
            else if (style.specialStyles && entry.months() >= 12) name += " · " + entry.months() + " months";
            List<String> textLines = wrap(name);
            if (style.layout.equals("paragraph")) { textLines = new ArrayList<>(textLines); textLines.add(""); }
            for (int offset = 0; offset < Math.max(1, textLines.size());) {
                if (page == null || names >= perPage || linesUsed + 1 + footer.size() > 14 || !month.equals(entryMonth)) {
                    if (page != null) finish(all, page, footer);
                    page = new ArrayList<>();
                    if (!header.isEmpty()) page.add(new Span(String.join("\n", header) + "\n\n", "black", ""));
                    linesUsed = header.isEmpty() ? 0 : header.size() + 1;
                    names = 0; month = entryMonth;
                }
                int room = Math.max(1, 14 - linesUsed - footer.size());
                int end = Math.min(textLines.size(), offset + room);
                String text = end > offset ? String.join("\n", textLines.subList(offset, end)) : "";
                String login = entry.detail("login");
                if (login.isBlank() && entry.name().matches("[A-Za-z0-9_]+")) login = entry.name().toLowerCase(Locale.ROOT);
                String url = style.clickableNames && !login.isBlank() && !removed ? "https://www.twitch.tv/" + URLEncoder.encode(login, StandardCharsets.UTF_8) : "";
                page.add(new Span(text + "\n", style.colour, url));
                linesUsed += Math.max(1, end - offset); offset = Math.max(offset + 1, end);
                if (offset < textLines.size()) { finish(all, page, footer); page = null; }
                else names++;
            }
        }
        if (page != null) finish(all, page, footer);
        if (all.isEmpty()) all.add(List.of(new Span("Subby\n\nYour subscribers will appear here.\n\nThank you for the support!", "black", "")));
        int volumes = Math.max(1, (all.size() + 99) / 100);
        int volume = requestedVolume == 0 ? volumes : requestedVolume;
        if (volume < 1 || volume > volumes) throw new IllegalArgumentException("Choose a volume between 1 and " + volumes + ".");
        List<List<Span>> selected = all.subList((volume - 1) * 100, Math.min(all.size(), volume * 100));
        List<String> pages = selected.stream().map(spans -> spans.stream().map(Span::text).collect(java.util.stream.Collectors.joining()).stripTrailing()).toList();
        String number = style.romanVolumes ? roman(volume) : Integer.toString(volume);
        String title = style.title.replace("{volume}", number).replace("{entries}", Integer.toString(entries.size()));
        return new Book(id, volume, truncate(title, 32), truncate(style.author, 32), pages, entries.size(), selected);
    }
    private static void finish(List<List<Span>> pages, List<Span> page, List<String> footer) {
        if (!footer.isEmpty()) page.add(new Span(String.join("\n", footer), "black", ""));
        pages.add(List.copyOf(page));
    }
    private static boolean contains(Set<String> users, Subscriber entry) {
        return users.stream().anyMatch(user -> user.equalsIgnoreCase(entry.name()) || user.equals(entry.userId()) || user.equalsIgnoreCase(entry.detail("login")));
    }
    private static boolean blocked(Subscriber entry, Options.Moderation moderation) {
        return contains(moderation.blockedUsers, entry) || moderation.respectBans && contains(moderation.bannedUsers, entry) && moderation.banExpiries.getOrDefault(entry.userId(), Long.MAX_VALUE) > System.currentTimeMillis() || moderation.blockedWords.stream()
                .anyMatch(word -> !word.isBlank() && (entry.name().toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT)) || entry.detail("login").toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT))));
    }
    public static Subscriber moderate(Subscriber entry, Options options) {
        if (blocked(entry, options.moderation)) return null;
        String name = options.book.nameMode.equals("login") && !entry.detail("login").isBlank() ? entry.detail("login") : entry.name();
        boolean hidden = false;
        if (contains(options.moderation.removedUsers, entry)) {
            if (!options.moderation.replaceRemoved) return null;
            name = "[Removed]";
            hidden = true;
        } else if (options.moderation.minecraftFilter && !entry.userId().isBlank()) {
            String selected = name;
            name = options.moderation.filteredNames.getOrDefault(filterKey(entry, options), "[Awaiting filter]");
            if (name.isBlank()) name = "[Filtered]";
            hidden = !name.equals(selected);
        }
        if (name.equals(entry.name()) && !hidden) return entry;
        return new Subscriber(entry.messageId(), entry.broadcasterId(), entry.userId(), name, entry.kind(), entry.tier(), entry.months(), entry.receivedAt(), hidden ? Map.of() : entry.details());
    }
    public static String filterKey(Subscriber entry, Options options) { return entry.userId() + ":" + (options.book.nameMode.equals("login") && !entry.detail("login").isBlank() ? entry.detail("login") : entry.name()); }
    private static String display(Subscriber entry, Options.BookStyle style) {
        return style.nameMode.equals("login") && !entry.detail("login").isBlank() ? entry.detail("login") : entry.name();
    }
    public static List<String> wrap(String text) {
        if (text.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (String paragraph : text.replace("\r", "").split("\n", -1)) {
            StringBuilder line = new StringBuilder(); int width = 0;
            for (int c : paragraph.codePoints().toArray()) {
                int size = c > 127 ? 10 : c == ' ' ? 4 : "il!.,:;|'".indexOf(c) >= 0 ? 3 : 6;
                if (width + size > 110 && !line.isEmpty()) { result.add(line.toString()); line.setLength(0); width = 0; }
                line.appendCodePoint(c); width += size;
            }
            result.add(line.toString());
        }
        return result;
    }
    static String truncate(String text, int length) {
        if (text.length() <= length) return text;
        int end = Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length;
        return text.substring(0, end);
    }
    private static String roman(int value) {
        if (value > 3999) return Integer.toString(value);
        int[] numbers = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
        String[] letters = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < numbers.length; i++) while (value >= numbers[i]) { result.append(letters[i]); value -= numbers[i]; }
        return result.toString();
    }
}
