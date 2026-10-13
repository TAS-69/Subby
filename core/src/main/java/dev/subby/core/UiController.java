package dev.subby.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static dev.subby.core.UiMenu.button;

public final class UiController {
    private record Prompt(long expires, Consumer<String> save, Runnable cancel, Platform.Audience audience, String permission) {}
    private final SubbyService service;
    private final Platform platform;
    private final Map<String, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<String, UUID> sessions = new HashMap<>();
    private final Map<String, String> pagePermissions = new HashMap<>();

    UiController(SubbyService service, Platform platform) { this.service = service; this.platform = platform; }

    private void page(Platform.Audience audience, String title, int rows, List<UiMenu.Button> buttons) {
        String permission = permission(title, pagePermissions.getOrDefault(audience.id(), "subby.configure"));
        if (!audience.permitted(permission)) { audience.message("You do not have permission for this profile menu."); return; }
        pagePermissions.put(audience.id(), permission);
        Rules expectedRules = service.rules();
        UUID session = UUID.randomUUID(); sessions.put(audience.id(), session);
        List<UiMenu.Button> guarded = buttons.stream().map(b -> new UiMenu.Button(b.slot(), b.icon(), b.label(), b.description(), () -> {
            if (!session.equals(sessions.get(audience.id()))) return;
            if (!audience.permitted("subby.use") && !audience.permitted("subby.admin")) { audience.message("You no longer have permission to manage Subby."); return; }
            if (!audience.permitted(permission)) { audience.message("You no longer have permission for this profile menu."); return; }
            if (permission.equals("subby.rules") && expectedRules != service.rules()) { audience.message("Rules changed in another menu. The list has been refreshed."); rules(audience); return; }
            try { b.action().run(); } catch (IllegalArgumentException e) { audience.message(e.getMessage()); }
        })).toList();
        platform.openMenu(audience, new UiMenu(title, rows, guarded));
    }
    private String permission(String title, String fallback) {
        String value = title.toLowerCase(Locale.ROOT);
        if (value.contains("your subscriber book") || value.contains("start here") || value.equals("subby - advanced") || value.contains("profiles")) return "subby.use";
        if (value.contains("connect twitch") || value.contains("connection settings")) return "subby.connect";
        if (value.contains("book appearance") || value.contains("names in your book")) return "subby.appearance";
        if (value.startsWith("subby - test")) return "subby.test";
        if (value.contains("saved volumes")) return "subby.book";
        if (value.contains("rule") || value.contains("condition") || value.contains("action") || value.contains("when this") || value.contains("do this") || value.contains("test without")) return "subby.rules";
        if (value.contains("safety and queue") || value.contains("choose effect player") || value.contains("presets and sharing") || value.contains("optional twitch chat")) return "subby.configure";
        if (value.contains("name moderation")) return "subby.moderate";
        if (value.contains("physical locations") || value.contains("bind a location") || value.contains("board content")) return "subby.bind";
        return fallback;
    }
    public void home(Platform.Audience audience) {
        if (!audience.player()) { service.command(audience, new String[]{"help"}); return; }
        Book book = service.currentBook();
        List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(button(4, "AMETHYST_SHARD", "Subby", () -> help(audience),
                "Subscriber names, written for you.", service.connectionStatus()));
        buttons.add(button(11, "ENDER_EYE", "Connect Twitch", () -> connection(audience),
                service.connectionStatus(), "Click for simple sign-in steps."));
        buttons.add(button(13, "WRITTEN_BOOK", "Get your subscriber book", () -> service.command(audience, new String[]{"book"}),
                book.entries() + " saved names", "Current volume: " + book.volume(), "A live book updates automatically."));
        buttons.add(button(15, "LECTERN", "Put it on a lectern", () -> {
            service.command(audience, new String[]{"lectern", "bind"});
        }, "Look at an empty lectern first.", "Click to place your live book there.", "Other books are kept safe."));
        buttons.add(button(29, "WRITABLE_BOOK", "What goes in the book?", () -> book(audience),
                "Choose which subscription names appear.", "Preview the book without saving fake names."));
        buttons.add(button(31, "PAPER", "Preview", () -> service.preview(audience),
                "See a sample using your book settings.", "Your subscriber history stays unchanged."));
        buttons.add(button(33, "CLOCK", "Saved volumes", () -> archives(audience, 0),
                "Recover earlier books for your library."));
        buttons.add(button(45, "PAPER", "Help", () -> help(audience), "Three steps to your first book."));
        buttons.add(button(49, "COMPARATOR", "Advanced " + (service.options().advancedEnabled ? "(on)" : "(off)"), () -> advanced(audience),
                "Optional tools for more experienced users.", "Extra interactions are off by default."));
        buttons.add(button(53, "BARRIER", "Close", () -> platform.closeMenu(audience)));
        page(audience, "Subby - Your subscriber book", 6, buttons);
    }
    private void help(Platform.Audience audience) {
        page(audience, "Subby - Start here", 3, List.of(
                button(10, "ENDER_EYE", "1. Connect Twitch", () -> connection(audience), "Sign in as the creator whose subscribers", "you want to write into the book."),
                button(13, "WRITTEN_BOOK", "2. Get your book", () -> service.command(audience, new String[]{"book"}), "Names arrive automatically while connected."),
                button(16, "LECTERN", "3. Keep playing", () -> home(audience), "Carry the book or bind an empty lectern.", "Subby saves names separately from the item.", "You can recover a lost book at any time."),
                button(22, "ARROW", "Back", () -> home(audience))));
    }
    private void connection(Platform.Audience audience) {
        if (service.connection().mode().equals("companion")) {
            page(audience, "Subby - Connect Twitch", 3, List.of(
                    button(11, "ENDER_EYE", "Connect in your browser", () -> { platform.closeMenu(audience); service.command(audience, new String[]{"connect"}); }, "Click for a private pairing code and link.", "Sign in with Twitch in your browser.", "Confirm the channel, then return to Minecraft."),
                    button(13, "PAPER", "Connection status", () -> service.command(audience, new String[]{"status"}), service.connectionStatus()),
                    button(15, "GRAY_DYE", "Disconnect", () -> { service.command(audience, new String[]{"disconnect"}); connection(audience); }, "Your saved names are kept."),
                    button(22, "ARROW", "Back", () -> home(audience))));
            return;
        }
        page(audience, "Subby - Connect Twitch", 4, List.of(
                button(10, "NAME_TAG", "Application client ID", () -> ask(audience, "Paste the Public Twitch application's client ID.", text ->
                        service.saveConfig(service.config().withClientId(text.trim()), audience, () -> connection(audience)), () -> connection(audience)),
                        service.config().clientId().isEmpty() ? "Not set yet" : "Client ID saved", "Twitch requires this once for your application.", "This is the public ID, never the secret."),
                button(12, "PAPER", "Where do I find it?", () -> {
                    platform.closeMenu(audience);
                    audience.link("Open Twitch applications", "https://dev.twitch.tv/console/apps");
                    audience.message("Register Subby as a Public application. Copy its Client ID, then return to this menu.");
                }, "Create a Public app in Twitch's developer console.", "Click to get the official link."),
                button(14, "ENDER_EYE", "Sign in with Twitch", () -> {
                    platform.closeMenu(audience); service.command(audience, new String[]{"login"});
                }, "A private activation link appears in chat.", "Open it and sign in as the broadcaster.", "No Twitch password is entered into Minecraft."),
                button(16, "LIME_DYE", "Connect saved account", () -> { service.command(audience, new String[]{"connect"}); connection(audience); }, service.connectionStatus()),
                button(28, "GRAY_DYE", "Disconnect", () -> { service.command(audience, new String[]{"disconnect"}); connection(audience); }, "Keep saved names and local sign-in."),
                button(30, "BARRIER", "Remove saved sign-in", () -> confirm(audience, "Remove Twitch sign-in?", () -> { service.command(audience, new String[]{"logout"}); connection(audience); }, () -> connection(audience)), "Does not delete your subscriber library."),
                button(34, "ARROW", "Back", () -> home(audience))));
    }
    private void book(Platform.Audience audience) {
        Options.BookStyle book = service.options().book;
        page(audience, "Subby - Names in your book", 4, List.of(
                toggle(10, "New subscribers", book.includeNew, () -> change(audience, o -> o.book.includeNew = !o.book.includeNew, () -> book(audience)), "Show new subscription names."),
                toggle(13, "Gift recipients", book.includeGifts, () -> change(audience, o -> o.book.includeGifts = !o.book.includeGifts, () -> book(audience)), "Write the name receiving the gift."),
                toggle(16, "Returning subscribers", book.includeResubs, () -> change(audience, o -> o.book.includeResubs = !o.book.includeResubs, () -> book(audience)), "Show shared resubscription messages.", "Automatic renewals are not sent by Twitch."),
                button(22, "PAPER", "Preview this book", () -> service.preview(audience), "No fake names are saved."),
                button(31, "ARROW", "Back", () -> home(audience))));
    }
    private UiMenu.Button toggle(int slot, String label, boolean enabled, Runnable action, String... description) {
        List<String> lines = new ArrayList<>(Arrays.asList(description)); lines.add("Click to turn " + (enabled ? "off" : "on") + ".");
        return new UiMenu.Button(slot, enabled ? "LIME_DYE" : "GRAY_DYE", label + " - " + (enabled ? "On" : "Off"), lines, action);
    }
    private void archives(Platform.Audience audience, int page) {
        int volumes = service.currentBook().volume(); List<UiMenu.Button> buttons = new ArrayList<>();
        for (int i = page * 27; i < Math.min(volumes, (page + 1) * 27); i++) {
            int volume = i + 1;
            buttons.add(button(i % 27, "WRITTEN_BOOK", "Volume " + volume, () -> service.command(audience, new String[]{"book", Integer.toString(volume)}), "Click to create an archive copy.", "Archive copies keep their saved contents."));
        }
        if (page > 0) buttons.add(button(27, "ARROW", "Previous", () -> archives(audience, page - 1)));
        if ((page + 1) * 27 < volumes) buttons.add(button(35, "ARROW", "Next", () -> archives(audience, page + 1)));
        buttons.add(button(31, "ARROW", "Back", () -> home(audience)));
        page(audience, "Subby - Saved volumes", 4, buttons);
    }
    private void advanced(Platform.Audience audience) {
        Options options = service.options();
        List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(toggle(4, "Enable extra interactions", options.advancedEnabled, () -> {
            if (!audience.permitted("subby.configure")) { audience.message("You do not have permission to enable optional interactions."); return; }
            if (options.advancedEnabled) change(audience, o -> o.advancedEnabled = false, () -> advanced(audience));
            else confirm(audience, "Enable optional interactions?", () -> change(audience, o -> o.advancedEnabled = true, () -> advanced(audience)), () -> advanced(audience));
        }, "Writing subscription names always comes first.", "Rules and extra events only run when enabled."));
        buttons.add(button(11, "REDSTONE", "Visual rule editor", () -> rules(audience), "Choose WHEN, optional conditions, and actions.", "No JSON editing is needed."));
        buttons.add(button(13, "WRITABLE_BOOK", "Book appearance", () -> appearance(audience), "Title, author, name format, and ordering."));
        buttons.add(button(15, "CLOCK", "Pause or resume effects", () -> { service.command(audience, new String[]{service.paused() ? "resume" : "pause"}); advanced(audience); }, service.paused() ? "Effects are paused." : "Effects are running.", "Names keep recording either way."));
        buttons.add(button(31, "PAPER", "Connection and queue", () -> dashboard(audience), "View connection, saved names, and optional effects."));
        buttons.add(button(29, "ENDER_EYE", "Connection settings", () -> connectionSettings(audience), "Companion service address and direct mode.", "Only needed when hosting your own service."));
        buttons.add(button(22, "NAME_TAG", "Effect player", () -> choosePlayer(audience), options.targetPlayer.isBlank() ? "No player selected. Only messages can run." : options.targetPlayer, "Choose who receives optional effects."));
        buttons.add(button(24, "COMPARATOR", "Safety and queue", () -> safety(audience), "Limits, Hardcore protection, and pacing."));
        buttons.add(button(33, "BARRIER", "Name moderation", () -> moderation(audience), "Hide, remove, or block names in the book."));
        buttons.add(button(40, "PAPER", "Test an event", () -> testEvent(audience), "Preview matching rules without running effects.", "No fake subscribers are saved."));
        buttons.add(button(38, "BOOK", "Presets and sharing", () -> presets(audience), "Start from a simple preset or share your rules.", "Imported extras stay off until you enable them."));
        buttons.add(button(42, "ENDER_EYE", "Optional Twitch chat", () -> bot(audience), "Moderator controls and feedback styles.", "Off by default. Uses your broadcaster account."));
        buttons.add(button(44, "BOOK", "Recover current subscribers", () -> confirm(audience, "Add missing current subscribers?", () -> service.recover(audience), () -> advanced(audience)), "Advanced: request Twitch's current subscriber list.", "Does not recover past events, dates, or months.", "Existing supporters are not added twice."));
        buttons.add(button(36, "BOOK", "Profiles", () -> platform.profiles(audience), "Separate libraries for different creators.", "Each profile has its own sign-in and rules."));
        buttons.add(button(18, "LECTERN", "Physical locations", () -> bindings(audience, 0), "Book chests, signs, text displays, and buttons.", "Books keep updating; other interactions require Advanced."));
        buttons.add(button(49, "ARROW", "Back to your book", () -> home(audience)));
        page(audience, "Subby - Advanced", 6, buttons);
    }
    private void connectionSettings(Platform.Audience audience) {
        ConnectionSettings settings = service.connection();
        page(audience, "Subby - Connection settings", 4, List.of(
                button(10, "ENDER_EYE", "Mode: " + settings.mode(), () -> confirm(audience, "Change connection mode?", () -> service.saveConnection(new ConnectionSettings(settings.mode().equals("companion") ? "direct" : "companion", settings.serviceUrl(), settings.label(), settings.autoConnect()), audience, () -> connectionSettings(audience)), () -> connectionSettings(audience)), "Companion: browser pairing (recommended).", "Direct: your own public Twitch application."),
                button(12, "PAPER", "Companion address", () -> ask(audience, "Enter your Companion service address (HTTPS, or http://localhost:3030).", text -> service.saveConnection(new ConnectionSettings(settings.mode(), text.trim(), settings.label(), settings.autoConnect()), audience, () -> connectionSettings(audience)), () -> connectionSettings(audience)), settings.serviceUrl()),
                button(14, "NAME_TAG", "Installation name", () -> ask(audience, "Enter the name shown on the browser pairing page.", text -> service.saveConnection(new ConnectionSettings(settings.mode(), settings.serviceUrl(), text, settings.autoConnect()), audience, () -> connectionSettings(audience)), () -> connectionSettings(audience)), settings.label()),
                button(16, "BARRIER", "Remove saved sign-in", () -> confirm(audience, "Unlink this installation?", () -> { service.command(audience, new String[]{"logout"}); connectionSettings(audience); }, () -> connectionSettings(audience)), "Unlinks Companion and removes local credentials.", "Your Minecraft subscriber library is kept."),
                button(22, "PAPER", "Direct Twitch setup", () -> connection(audience), "Only used in Direct mode."),
                button(31, "ARROW", "Back", () -> advanced(audience))));
    }
    private void appearance(Platform.Audience audience) {
        Options.BookStyle style = service.options().book; List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(button(10, "NAME_TAG", "Book title", () -> ask(audience, "Enter a title. Use {volume} for the volume number.", text -> change(audience, o -> o.book.title = text, () -> appearance(audience)), () -> appearance(audience)), style.title));
        buttons.add(button(12, "WRITABLE_BOOK", "Author", () -> ask(audience, "Enter the book's author.", text -> change(audience, o -> o.book.author = text, () -> appearance(audience)), () -> appearance(audience)), style.author));
        buttons.add(button(14, "PAPER", "Name format", () -> ask(audience, "Enter the name format. Example: {display_name} - {months} months", text -> change(audience, o -> o.book.line = text, () -> appearance(audience)), () -> appearance(audience)), style.line));
        buttons.add(button(16, "BOOK", "Ordering: " + style.sort, () -> change(audience, o -> o.book.sort = o.book.sort.equals("chronological") ? "alphabetical" : "chronological", () -> appearance(audience)), "Click to switch order."));
        buttons.add(button(19, "PAPER", "Header", () -> ask(audience, "Enter the page header (use a single dash to clear).", text -> change(audience, o -> o.book.header = blank(text), () -> appearance(audience)), () -> appearance(audience)), style.header));
        buttons.add(button(21, "PAPER", "Footer", () -> ask(audience, "Enter the page footer (single dash to clear).", text -> change(audience, o -> o.book.footer = blank(text), () -> appearance(audience)), () -> appearance(audience)), style.footer));
        buttons.add(button(23, "BOOK", "Opening page", () -> ask(audience, "Enter the opening message (single dash to clear).", text -> change(audience, o -> o.book.opening = blank(text), () -> appearance(audience)), () -> appearance(audience)), style.opening));
        buttons.add(button(25, "BOOK", "Layout: " + style.layout, () -> change(audience, o -> o.book.layout = next(List.of("compact", "paragraph", "page"), o.book.layout), () -> appearance(audience)), "Click to choose compact, paragraphs, or one name per page."));
        buttons.add(button(28, "NAME_TAG", "Names per page: " + style.namesPerPage, () -> change(audience, o -> o.book.namesPerPage = o.book.namesPerPage % 8 + 1, () -> appearance(audience)), "Click to choose 1–8 names per page."));
        buttons.add(button(30, "NAME_TAG", "Text colour: " + style.colour, () -> change(audience, o -> o.book.colour = next(List.of("black", "dark_blue", "dark_green", "dark_red", "dark_purple", "gold"), o.book.colour), () -> appearance(audience)), "Click to cycle colours."));
        buttons.add(toggle(32, "Clickable names", style.clickableNames, () -> change(audience, o -> o.book.clickableNames = !o.book.clickableNames, () -> appearance(audience)), "Links names to their Twitch page."));
        buttons.add(toggle(34, "One entry per supporter", style.registryView, () -> change(audience, o -> o.book.registryView = !o.book.registryView, () -> appearance(audience)), "Updates each supporter instead of listing every event."));
        buttons.add(toggle(37, "Roman volume numbers", style.romanVolumes, () -> change(audience, o -> o.book.romanVolumes = !o.book.romanVolumes, () -> appearance(audience))));
        buttons.add(toggle(39, "Tier symbols", style.tierSymbols, () -> change(audience, o -> o.book.tierSymbols = !o.book.tierSymbols, () -> appearance(audience))));
        buttons.add(toggle(41, "Gift and milestone styling", style.specialStyles, () -> change(audience, o -> o.book.specialStyles = !o.book.specialStyles, () -> appearance(audience))));
        buttons.add(toggle(43, "Monthly headings", style.monthlyHeaders, () -> change(audience, o -> o.book.monthlyHeaders = !o.book.monthlyHeaders, () -> appearance(audience))));
        buttons.add(button(45, "PAPER", "Preview", () -> service.preview(audience), "Check the appearance before using it."));
        buttons.add(button(47, "PAPER", "More text options", () -> textStyle(audience), "Prefix, suffix, and login/display name choice."));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Book appearance", 6, buttons);
    }
    private void textStyle(Platform.Audience audience) {
        Options.BookStyle style = service.options().book;
        page(audience, "Subby - Book appearance text", 3, List.of(
                button(10, "PAPER", "Before each name", () -> ask(audience, "Enter text before each name, or a single dash to clear.", text -> change(audience, o -> o.book.prefix = blank(text), () -> textStyle(audience)), () -> textStyle(audience)), style.prefix),
                button(13, "PAPER", "After each name", () -> ask(audience, "Enter text after each name, or a single dash to clear.", text -> change(audience, o -> o.book.suffix = blank(text), () -> textStyle(audience)), () -> textStyle(audience)), style.suffix),
                button(16, "NAME_TAG", "Name choice: " + style.nameMode, () -> change(audience, o -> o.book.nameMode = o.book.nameMode.equals("display") ? "login" : "display", () -> textStyle(audience)), "Display: chosen Twitch display name.", "Login: Twitch username."),
                button(19, "NAME_TAG", "Book recipient", () -> bookPlayer(audience), style.boundPlayer.isEmpty() ? "Any player holding a live book" : style.boundPlayer, "Optionally deliver and update books for one player."),
                button(22, "ARROW", "Back", () -> appearance(audience))));
    }
    private void bookPlayer(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>(); List<String> players = platform.players();
        for (int i = 0; i < Math.min(45, players.size()); i++) { String name = players.get(i); buttons.add(button(i, "NAME_TAG", name, () -> change(audience, o -> o.book.boundPlayer = name, () -> textStyle(audience)))); }
        buttons.add(button(45, "PAPER", "Any player holding the live book", () -> change(audience, o -> o.book.boundPlayer = "", () -> textStyle(audience))));
        buttons.add(button(49, "ARROW", "Back", () -> textStyle(audience))); page(audience, "Subby - Book appearance recipient", 6, buttons);
    }
    private void dashboard(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>(); List<String> data = service.dashboard();
        for (int i = 0; i < data.size(); i++) buttons.add(button(10 + i, "PAPER", data.get(i), () -> dashboard(audience), "Click to refresh."));
        buttons.add(button(28, "ENDER_EYE", "Connection", () -> connection(audience)));
        buttons.add(button(30, "CLOCK", service.paused() ? "Resume effects" : "Pause effects", () -> { service.command(audience, new String[]{service.paused() ? "resume" : "pause"}); dashboard(audience); }, "Subscription names keep recording."));
        buttons.add(button(32, "PAPER", "Test without effects", () -> testEvent(audience)));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Connection and queue", 6, buttons);
    }
    private String blank(String text) { return text.equals("-") ? "" : text; }
    private String next(List<String> choices, String current) { return choices.get((choices.indexOf(current) + 1) % choices.size()); }
    private void rules(Platform.Audience audience) {
        rules(audience, 0);
    }
    private void rules(Platform.Audience audience, int page) {
        List<UiMenu.Button> buttons = new ArrayList<>(); List<Rules.Rule> rules = service.rules().rules();
        for (int i = page * 36; i < Math.min((page + 1) * 36, rules.size()); i++) {
            int index = i; Rules.Rule rule = rules.get(i);
            buttons.add(button(i % 36, "REDSTONE", rule.event() + " -> " + rule.actions().size() + " actions", () -> rule(audience, index),
                    "Minimum months: " + rule.minimumMonths(), "Cooldown: " + rule.cooldownSeconds() + " seconds", "Click to edit visually."));
        }
        if (page > 0) buttons.add(button(45, "ARROW", "Previous", () -> rules(audience, page - 1)));
        buttons.add(button(46, "LIME_DYE", "Add a rule", () -> chooseEvent(audience, -1), "Choose what happens when a Twitch event arrives."));
        if ((page + 1) * 36 < rules.size()) buttons.add(button(53, "ARROW", "Next", () -> rules(audience, page + 1)));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Your optional rules", 6, buttons);
    }
    private void chooseEvent(Platform.Audience audience, int index) {
        List<UiMenu.Button> buttons = new ArrayList<>();
        for (EventType event : EventType.values()) {
            Runnable create = () -> editRule(audience, index, old -> old.with(event.id, old.minimumMonths(), old.cooldownSeconds(), old.actions()), () -> rule(audience, index < 0 ? service.rules().rules().size() - 1 : index));
            buttons.add(button(event.ordinal(), "WRITTEN_BOOK", event.label, () -> {
                if (event.subscription()) create.run();
                else if (service.connection().mode().equals("direct")) audience.message("Extra events require Companion mode. Change it in Advanced > Connection settings.");
                else confirm(audience, "Add this optional event?", () -> change(audience, o -> o.events.add(event.id), () -> { audience.message("Enable Extra interactions when ready, then reconnect Twitch to approve this optional event permission."); create.run(); }), () -> chooseEvent(audience, index));
            }, event.subscription() ? "Included with your subscriber book." : "Optional. Companion requests permission on reconnect.", "Click to use this event."));
        }
        buttons.add(button(49, "ARROW", "Back", () -> rules(audience)));
        page(audience, "Subby - WHEN this happens", 6, buttons);
    }
    private void rule(Platform.Audience audience, int index) {
        if (index < 0 || index >= service.rules().rules().size()) { rules(audience); return; }
        Rules.Rule rule = service.rules().rules().get(index); List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(button(4, "ENDER_EYE", "WHEN: " + rule.event(), () -> chooseEvent(audience, index), "Click to change the Twitch event."));
        buttons.add(button(10, "CLOCK", "IF: months at least " + rule.minimumMonths(), () -> ask(audience, "Enter the minimum subscription months (0 for any).", text -> editRule(audience, index, r -> r.with(r.event(), number(text, 0, 10000), r.cooldownSeconds(), r.actions()), () -> rule(audience, index)), () -> rule(audience, index)), "Optional condition."));
        buttons.add(button(16, "CLOCK", "Cooldown: " + rule.cooldownSeconds() + " seconds", () -> ask(audience, "Enter the cooldown in seconds (0–86400).", text -> editRule(audience, index, r -> r.with(r.event(), r.minimumMonths(), number(text, 0, 86400), r.actions()), () -> rule(audience, index)), () -> rule(audience, index)), "Stops this rule's effects from repeating too quickly.", "Every subscription name is still recorded."));
        buttons.add(button(13, "COMPARATOR", "More conditions", () -> conditions(audience, index), "Tier, minimum amount, reward, priority, random pool."));
        for (int i = 0; i < rule.actions().size(); i++) {
            int action = i; Rules.Action value = rule.actions().get(i);
            buttons.add(button(27 + i, icon(value.type()), "DO: " + value.type(), () -> action(audience, index, action), value.value(), "Click to edit or remove."));
        }
        buttons.add(button(45, "LIME_DYE", "Add an action", () -> chooseAction(audience, index), "One event can trigger several actions."));
        buttons.add(button(47, "BARRIER", "Delete this rule", () -> confirm(audience, "Delete this optional rule?", () -> service.saveRules(r -> { List<Rules.Rule> list = new ArrayList<>(r.rules()); list.remove(index); return new Rules(list); }, audience, () -> rules(audience)), () -> rule(audience, index)), "Book recording is unaffected."));
        buttons.add(button(49, "ARROW", "Back", () -> rules(audience)));
        page(audience, "Subby - Edit optional rule", 6, buttons);
    }
    private String icon(String action) { return switch (action) { case "sound" -> "BELL"; case "function" -> "COMMAND_BLOCK"; default -> "PAPER"; }; }
    private void chooseAction(Platform.Audience audience, int index) {
        List<UiMenu.Button> buttons = new ArrayList<>();
        for (int i = 0; i < Rules.Action.TYPES.size(); i++) {
            String type = Rules.Action.TYPES.get(i);
            String prompt = switch (type) {
                case "message", "title", "subtitle", "actionbar", "book", "token" -> "Enter text. Use {user}, {months}, {tier}, {bits}, {gift_count}, or {reward}.";
                case "chest", "sign", "display", "redstone" -> "Choose a matching physical binding from Advanced > Physical locations.";
                case "command" -> "Enter a fixed server command without a leading slash. It must also be allowed in Safety settings.";
                case "score" -> "Enter an existing scoreboard objective name.";
                default -> "Enter a fixed Minecraft resource ID for " + type + ".";
            };
            buttons.add(button(i, icon(type), actionLabel(type), () -> {
                if (Set.of("sound", "particle", "give", "effect", "mob").contains(type)) resources(audience, index, type, prompt);
                else if (Set.of("chest", "sign", "display", "redstone").contains(type)) bindingAction(audience, index, type);
                else addAction(audience, index, type, prompt);
            }, "Click to configure this action.", Set.of("command", "function", "mob", "redstone").contains(type) ? "Requires explicit safety approval." : "Effects require a selected player (except chat and book)."));
        }
        buttons.add(button(31, "ARROW", "Back", () -> rule(audience, index)));
        page(audience, "Subby - DO this", 4, buttons);
    }
    private void addAction(Platform.Audience audience, int index, String type, String prompt) {
        ask(audience, prompt, text -> addActionValue(audience, index, type, text), () -> rule(audience, index));
    }
    private void addActionValue(Platform.Audience audience, int index, String type, String text) {
        Rules.Action action = new Rules.Action(type, text);
        editRule(audience, index, old -> { List<Rules.Action> actions = new ArrayList<>(old.actions()); actions.add(action); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), actions); }, () -> rule(audience, index));
    }
    private String actionLabel(String type) {
        return switch (type) {
            case "message" -> "Send a chat message"; case "title" -> "Show a title"; case "subtitle" -> "Show a subtitle"; case "actionbar" -> "Show text above the hotbar";
            case "sound" -> "Play a sound"; case "particle" -> "Show particles"; case "give" -> "Give an item"; case "effect" -> "Apply a potion effect"; case "mob" -> "Spawn a mob";
            case "score" -> "Add to a scoreboard"; case "advancement" -> "Grant an advancement"; case "function" -> "Run a datapack function"; case "command" -> "Run an allowed command";
            case "book" -> "Write this event into the book"; case "chest" -> "Put an item in a bound chest"; case "sign" -> "Change a bound sign"; case "display" -> "Change floating text";
            case "redstone" -> "Press a bound button"; case "token" -> "Give a supporter keepsake"; default -> type;
        };
    }
    private void resources(Platform.Audience audience, int index, String type, String prompt) {
        List<String> choices = switch (type) {
            case "sound" -> List.of("minecraft:block.note_block.bell", "minecraft:entity.player.levelup", "minecraft:ui.toast.challenge_complete", "minecraft:entity.firework_rocket.twinkle");
            case "particle" -> List.of("minecraft:heart", "minecraft:happy_villager", "minecraft:portal", "minecraft:composter");
            case "give" -> List.of("minecraft:diamond", "minecraft:emerald", "minecraft:golden_apple", "minecraft:bread");
            case "effect" -> List.of("minecraft:speed", "minecraft:regeneration", "minecraft:night_vision", "minecraft:jump_boost");
            default -> List.of("minecraft:cow", "minecraft:sheep", "minecraft:chicken", "minecraft:zombie");
        };
        List<UiMenu.Button> buttons = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) { String id = choices.get(i); String label = id.substring(id.indexOf(':') + 1).replace('_', ' ').replace('.', ' '); buttons.add(button(10 + i * 2, icon(type), label, () -> addActionValue(audience, index, type, id), id)); }
        buttons.add(button(19, "PAPER", "Enter another ID", () -> addAction(audience, index, type, prompt)));
        buttons.add(button(22, "ARROW", "Back", () -> chooseAction(audience, index)));
        page(audience, "Subby - Choose action", 3, buttons);
    }
    private void bindingAction(Platform.Audience audience, int index, String type) {
        List<UiMenu.Button> buttons = new ArrayList<>(); List<Binding> bindings = service.bindings().stream().filter(b -> b.kind().equals(type)).toList();
        for (int i = 0; i < Math.min(45, bindings.size()); i++) { Binding binding = bindings.get(i); buttons.add(button(i, "LECTERN", binding.name(), () -> addActionValue(audience, index, type, binding.name()), "Mode: " + binding.mode())); }
        buttons.add(button(49, "ARROW", "Back", () -> chooseAction(audience, index), bindings.isEmpty() ? "Create a matching physical location in Advanced first." : "Choose which bound location receives this action."));
        page(audience, "Subby - Choose action target", 6, buttons);
    }
    private void action(Platform.Audience audience, int index, int actionIndex) {
        Rules.Action action = service.rules().rules().get(index).actions().get(actionIndex);
        page(audience, "Subby - Edit action", 3, List.of(
                button(11, icon(action.type()), "Change " + action.type(), () -> ask(audience, "Enter the new " + action.type() + " value.", text -> {
                    Rules.Action replacement = new Rules.Action(action.type(), text, action.parameters(), action.weight());
                    editRule(audience, index, old -> { List<Rules.Action> actions = new ArrayList<>(old.actions()); actions.set(actionIndex, replacement); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), actions); }, () -> rule(audience, index));
                }, () -> rule(audience, index)), action.value()),
                button(13, "COMPARATOR", "Amount, duration and weight", () -> actionSettings(audience, index, actionIndex), "Optional numeric settings."),
                button(15, "BARRIER", "Remove this action", () -> editRule(audience, index, old -> { List<Rules.Action> actions = new ArrayList<>(old.actions()); actions.remove(actionIndex); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), actions); }, () -> rule(audience, index))),
                button(22, "ARROW", "Back", () -> rule(audience, index))));
    }
    private void editRule(Platform.Audience audience, int index, java.util.function.UnaryOperator<Rules.Rule> update, Runnable done) {
        Rules.Rule expected = index < 0 ? new Rules.Rule("sub", 0, 2, List.of()) : service.rules().rules().get(index);
        Rules.Rule replacement = update.apply(expected);
        service.saveRules(old -> {
            List<Rules.Rule> rules = new ArrayList<>(old.rules());
            if (index < 0) rules.add(replacement);
            else {
                if (!rules.get(index).equals(expected)) throw new IllegalArgumentException("This rule changed in another menu. Reopen the rule list.");
                rules.set(index, replacement);
            }
            return new Rules(rules);
        }, audience, () -> {
            if (index >= 0) done.run();
            else { List<Rules.Rule> list = service.rules().rules(); for (int i = 0; i < list.size(); i++) if (list.get(i).conditions().id().equals(replacement.conditions().id())) { rule(audience, i); return; } rules(audience); }
        });
    }
    private void choosePlayer(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>(); List<String> players = platform.players();
        for (int i = 0; i < Math.min(45, players.size()); i++) { String player = players.get(i); buttons.add(button(i, "NAME_TAG", player, () -> change(audience, o -> o.targetPlayer = player, () -> advanced(audience)), "Optional effects apply to this player.")); }
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Choose effect player", 6, buttons);
    }
    private void conditions(Platform.Audience audience, int index) {
        Rules.Conditions c = service.rules().rules().get(index).conditions();
        page(audience, "Subby - Optional conditions", 4, List.of(
                toggle(4, "Rule enabled", c.enabled(), () -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), !v.enabled(), v.minimumTier(), v.minimumAmount(), v.rewardId(), v.priority(), v.randomPool(), v.minimumCombo())), () -> conditions(audience, index))),
                button(10, "BOOK", "Minimum tier: " + c.minimumTier(), () -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), (v.minimumTier() + 1) % 4, v.minimumAmount(), v.rewardId(), v.priority(), v.randomPool(), v.minimumCombo())), () -> conditions(audience, index)), "0 means any tier; click to choose 0–3."),
                button(12, "COMPARATOR", "Minimum amount: " + c.minimumAmount(), () -> ask(audience, "Minimum Bits, gifts, raiders, Hype Train level, or months (0 for any).", text -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), v.minimumTier(), number(text, 0, 1000000), v.rewardId(), v.priority(), v.randomPool(), v.minimumCombo())), () -> conditions(audience, index)), () -> conditions(audience, index)), "Uses the amount associated with this event."),
                button(14, "NAME_TAG", "Reward ID", () -> ask(audience, "Enter a channel point reward ID, or a single dash for any reward.", text -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), v.minimumTier(), v.minimumAmount(), blank(text), v.priority(), v.randomPool(), v.minimumCombo())), () -> conditions(audience, index)), () -> conditions(audience, index)), c.rewardId().isBlank() ? "Any reward" : c.rewardId()),
                button(16, "CLOCK", "Priority: " + c.priority(), () -> ask(audience, "Enter priority from -100 to 100. Higher values run first.", text -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), v.minimumTier(), v.minimumAmount(), v.rewardId(), number(text, -100, 100), v.randomPool(), v.minimumCombo())), () -> conditions(audience, index)), () -> conditions(audience, index))),
                toggle(22, "Choose one random action", c.randomPool(), () -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), v.minimumTier(), v.minimumAmount(), v.rewardId(), v.priority(), !v.randomPool(), v.minimumCombo())), () -> conditions(audience, index)), "Off: run all actions. On: choose one by its weight."),
                button(25, "CLOCK", "Minimum combo: " + c.minimumCombo(), () -> ask(audience, "Minimum events in the combo window (0 for any). Set the window in Safety and queue.", text -> editRule(audience, index, r -> r.condition(v -> new Rules.Conditions(v.id(), v.enabled(), v.minimumTier(), v.minimumAmount(), v.rewardId(), v.priority(), v.randomPool(), number(text, 0, 10000))), () -> conditions(audience, index)), () -> conditions(audience, index)), "Subscriptions, resubs and recipients count together.", "Other event kinds count separately."),
                button(31, "ARROW", "Back", () -> rule(audience, index))));
    }
    private void actionSettings(Platform.Audience audience, int ruleIndex, int actionIndex) {
        Rules.Action action = service.rules().rules().get(ruleIndex).actions().get(actionIndex);
        List<UiMenu.Button> buttons = new ArrayList<>();
        String[] keys = {"amount", "seconds", "level", "weight"};
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            buttons.add(button(10 + i * 2, "COMPARATOR", key + ": " + (key.equals("weight") ? action.weight() : action.parameters().getOrDefault(key, key.equals("seconds") ? "10" : key.equals("level") ? "0" : "1")), () -> ask(audience, "Enter " + key + " (amount 1–4096, seconds 1–3600, level 0–10, weight 1–1000).", text -> editRule(audience, ruleIndex, old -> {
                List<Rules.Action> actions = new ArrayList<>(old.actions()); Rules.Action current = actions.get(actionIndex);
                Rules.Action replacement = key.equals("weight") ? new Rules.Action(current.type(), current.value(), current.parameters(), number(text, 1, 1000)) : current.parameter(key, text.trim());
                actions.set(actionIndex, replacement); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), actions);
            }, () -> actionSettings(audience, ruleIndex, actionIndex)), () -> actionSettings(audience, ruleIndex, actionIndex)), "Only settings relevant to the action are used."));
        }
        buttons.add(button(22, "ARROW", "Back", () -> action(audience, ruleIndex, actionIndex)));
        if (action.type().equals("chest") || Set.of("sign", "display").contains(action.type())) {
            String key = action.type().equals("chest") ? "item" : "text";
            buttons.add(button(19, "PAPER", key + ": " + action.parameters().getOrDefault(key, key.equals("item") ? "minecraft:diamond" : "{user}"), () -> ask(audience, "Enter " + (key.equals("item") ? "a fixed item ID." : "a message template (up to 100 characters)."), text -> editRule(audience, ruleIndex, old -> {
                List<Rules.Action> list = new ArrayList<>(old.actions()); list.set(actionIndex, list.get(actionIndex).parameter(key, text)); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), list);
            }, () -> actionSettings(audience, ruleIndex, actionIndex)), () -> actionSettings(audience, ruleIndex, actionIndex))));
        }
        if (action.type().equals("token")) buttons.add(button(19, "LECTERN", "Keepsake destination", () -> ask(audience, "Enter a bound chest/archive name, or a single dash to give the selected player.", text -> editRule(audience, ruleIndex, old -> {
            List<Rules.Action> list = new ArrayList<>(old.actions()); list.set(actionIndex, list.get(actionIndex).parameter("chest", blank(text.trim()))); return old.with(old.event(), old.minimumMonths(), old.cooldownSeconds(), list);
        }, () -> actionSettings(audience, ruleIndex, actionIndex)), () -> actionSettings(audience, ruleIndex, actionIndex)), action.parameters().getOrDefault("chest", "Selected player")));
        page(audience, "Subby - Action settings", 3, buttons);
    }
    private void safety(Platform.Audience audience) {
        Options o = service.options(); List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(toggle(4, "Hardcore protection", o.safety.hardcoreSafe, () -> change(audience, v -> v.safety.hardcoreSafe = !v.safety.hardcoreSafe, () -> safety(audience)), "Blocks dangerous actions in Hardcore worlds."));
        buttons.add(toggle(10, "Allow dangerous actions", o.safety.destructiveActions, () -> confirm(audience, "Change dangerous actions?", () -> change(audience, v -> v.safety.destructiveActions = !v.safety.destructiveActions, () -> safety(audience)), () -> safety(audience)), "Commands, functions, mobs, and harmful effects.", "Off by default. Review every rule first."));
        buttons.add(toggle(12, "Skip effects near bosses or raids", o.safety.skipDuringBoss, () -> change(audience, v -> v.safety.skipDuringBoss = !v.safety.skipDuringBoss, () -> safety(audience)), "Checks nearby dragons, withers, and active raids."));
        buttons.add(button(14, "NAME_TAG", "Minimum health: " + o.safety.minimumHealth, () -> ask(audience, "Minimum health in half-hearts (0–20).", text -> change(audience, v -> v.safety.minimumHealth = number(text, 0, 20), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(16, "COMMAND_BLOCK", "Allow a fixed command", () -> ask(audience, "Enter an exact allowed command, e.g. function subby:on_sub. No leading slash.", text -> confirm(audience, "Allow server authority?", () -> change(audience, v -> v.safety.allowedCommands.add(text), () -> safety(audience)), () -> safety(audience)), () -> safety(audience)), o.safety.allowedCommands.size() + " exact commands allowed.", "Functions also require this explicit allowlist."));
        buttons.add(button(19, "COMPARATOR", "Items per minute: " + o.safety.itemsPerMinute, () -> ask(audience, "Maximum items per minute (0–4096).", text -> change(audience, v -> v.safety.itemsPerMinute = number(text, 0, 4096), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(21, "COMPARATOR", "Mobs per minute: " + o.safety.mobsPerMinute, () -> ask(audience, "Maximum mobs per minute (0–100).", text -> change(audience, v -> v.safety.mobsPerMinute = number(text, 0, 100), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(23, "CLOCK", "Effects per interval: " + o.queue.effectsPerTick, () -> ask(audience, "Effects per queue interval (1–100).", text -> change(audience, v -> v.queue.effectsPerTick = number(text, 1, 100), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(25, "CLOCK", "Interval: " + o.queue.intervalSeconds + " seconds", () -> ask(audience, "Queue interval in seconds (1–60).", text -> change(audience, v -> v.queue.intervalSeconds = number(text, 1, 60), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(28, "CLOCK", "Global cooldown: " + o.queue.globalCooldownSeconds, () -> ask(audience, "Global cooldown in seconds (0–3600).", text -> change(audience, v -> v.queue.globalCooldownSeconds = number(text, 0, 3600), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(30, "COMPARATOR", "Queue capacity: " + o.queue.capacity, () -> ask(audience, "Maximum pending effects (1–10000). Names are always saved.", text -> change(audience, v -> v.queue.capacity = number(text, 1, 10000), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(32, "CLOCK", "Combo window: " + o.queue.comboSeconds + " seconds", () -> ask(audience, "Combo window in seconds (0–60). Use {combo_count} in messages.", text -> change(audience, v -> v.queue.comboSeconds = number(text, 0, 60), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(34, "BARRIER", "Clear command allowlist", () -> confirm(audience, "Clear allowed commands?", () -> change(audience, v -> v.safety.allowedCommands.clear(), () -> safety(audience)), () -> safety(audience))));
        buttons.add(button(37, "PAPER", "Allowed dimensions", () -> ask(audience, "Enter dimension IDs separated by commas, or a single dash for all dimensions.", text -> change(audience, v -> v.safety.allowedDimensions = text.equals("-") ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.stream(text.split(",")).map(String::trim).toList()), () -> safety(audience)), () -> safety(audience)), o.safety.allowedDimensions.isEmpty() ? "All dimensions" : String.join(", ", o.safety.allowedDimensions)));
        buttons.add(toggle(39, "Remember queued effects", o.queue.persistent, () -> change(audience, v -> v.queue.persistent = !v.queue.persistent, () -> safety(audience)), "On: restore pending effects after a restart."));
        buttons.add(toggle(41, "Combine repeated effects", o.queue.combineEffects, () -> change(audience, v -> v.queue.combineEffects = !v.queue.combineEffects, () -> safety(audience)), "Uses the combo window to wait and combine matching actions.", "Messages can show {combo_count}; every book entry is saved."));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Safety and queue", 6, buttons);
    }
    private void moderation(Platform.Audience audience) {
        Options.Moderation m = service.options().moderation;
        page(audience, "Subby - Name moderation", 4, List.of(
                button(10, "BARRIER", "Block a name or user ID", () -> ask(audience, "Enter the Twitch name, login, or user ID to hide and block from effects.", text -> change(audience, o -> o.moderation.blockedUsers.add(service.canonicalUser(text)), () -> moderation(audience)), () -> moderation(audience)), m.blockedUsers.size() + " users blocked."),
                button(12, "PAPER", "Hide an existing name", () -> ask(audience, "Enter the Twitch name, login, or user ID to remove from books.", text -> change(audience, o -> o.moderation.removedUsers.add(service.canonicalUser(text)), () -> moderation(audience)), () -> moderation(audience)), "The saved history is retained privately.", "Reprinted live books reflect the change."),
                button(14, "NAME_TAG", "Restore a user", () -> ask(audience, "Enter the blocked or removed name or ID to restore.", text -> change(audience, o -> { String id = service.canonicalUser(text); o.moderation.blockedUsers.removeIf(v -> v.equalsIgnoreCase(text.trim()) || v.equals(id)); o.moderation.removedUsers.removeIf(v -> v.equalsIgnoreCase(text.trim()) || v.equals(id)); }, () -> moderation(audience)), () -> moderation(audience))),
                button(16, "BARRIER", "Block a word", () -> ask(audience, "Enter a word to hide in subscriber names.", text -> change(audience, o -> o.moderation.blockedWords.add(text.trim()), () -> moderation(audience)), () -> moderation(audience)), m.blockedWords.size() + " words blocked."),
                button(20, "PAPER", "Unblock a word", () -> ask(audience, "Enter the word to remove from your filter.", text -> change(audience, o -> o.moderation.blockedWords.removeIf(v -> v.equalsIgnoreCase(text.trim())), () -> moderation(audience)), () -> moderation(audience))),
                toggle(22, "Use [Removed] placeholders", m.replaceRemoved, () -> change(audience, o -> o.moderation.replaceRemoved = !o.moderation.replaceRemoved, () -> moderation(audience)), "Off: omit removed names completely."),
                toggle(19, "Respect Twitch bans and timeouts", m.respectBans, () -> confirm(audience, "Change Twitch ban filtering?", () -> change(audience, o -> { o.moderation.respectBans = !o.moderation.respectBans; if (o.moderation.respectBans) o.events.addAll(Set.of("ban", "unban")); }, () -> { audience.message("Reconnect to approve Twitch moderation events. Existing bans are not fetched automatically."); moderation(audience); }), () -> moderation(audience)), "Optional Twitch moderation permission.", "Observed banned users are hidden from books/effects."),
                button(25, "NAME_TAG", "Mark a founder", () -> ask(audience, "Enter a Twitch name or user ID to mark as a founder. Type -name to clear a mark.", text -> change(audience, o -> { if (text.startsWith("-")) o.moderation.founders.removeIf(v -> v.equalsIgnoreCase(text.substring(1).trim())); else o.moderation.founders.add(text.trim()); }, () -> moderation(audience)), () -> moderation(audience)), "Manual founder labels; Twitch does not send this on subscriptions.", "Enable special styling in Book appearance."),
                toggle(28, "Use configured Minecraft text filter", m.minecraftFilter, () -> {
                    if (!m.minecraftFilter && !service.nameFilterAvailable()) { audience.message("Select an online effect player on a Fabric/NeoForge server with a configured Minecraft text filter. Paper does not expose this filter."); return; }
                    confirm(audience, "Change server name filtering?", () -> change(audience, o -> o.moderation.minecraftFilter = !o.moderation.minecraftFilter, () -> moderation(audience)), () -> moderation(audience));
                }, "Names are checked by your server's configured filter.", "Unprocessed names stay hidden until the player is online.", "Raw subscriber history is retained privately."),
                button(31, "ARROW", "Back", () -> advanced(audience))));
    }
    private void testEvent(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>();
        for (EventType event : EventType.values()) buttons.add(button(event.ordinal(), "PAPER", event.label, () -> testSample(audience, event, "ExampleViewer", event == EventType.BITS ? 100 : event == EventType.RAID ? 25 : event == EventType.RESUB ? 12 : 1, 1, 1, ""), "Choose a local example event.", "Preview safely or explicitly run optional effects."));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Test without effects", 6, buttons);
    }
    private void testSample(Platform.Audience audience, EventType event, String name, int amount, int tier, int combo, String reward) {
        List<UiMenu.Button> buttons = new ArrayList<>();
        buttons.add(button(10, "NAME_TAG", "Example name: " + name, () -> ask(audience, "Enter a name for this local test. It is never saved as a subscriber.", text -> testSample(audience, event, Subscriber.safeName(text), amount, tier, combo, reward), () -> testSample(audience, event, name, amount, tier, combo, reward))));
        buttons.add(button(12, "COMPARATOR", "Amount: " + amount, () -> ask(audience, "Enter example Bits, raiders, gifts, Hype Train level, or resub months (0–1000000).", text -> testSample(audience, event, name, number(text, 0, 1000000), tier, combo, reward), () -> testSample(audience, event, name, amount, tier, combo, reward))));
        buttons.add(button(14, "BOOK", "Tier: " + tier, () -> testSample(audience, event, name, amount, tier % 3 + 1, combo, reward), "Click to choose tier 1–3."));
        buttons.add(button(16, "CLOCK", "Combo: " + combo, () -> ask(audience, "Enter the example combo count (1–10000).", text -> testSample(audience, event, name, amount, tier, number(text, 1, 10000), reward), () -> testSample(audience, event, name, amount, tier, combo, reward))));
        if (event == EventType.REWARD) buttons.add(button(19, "NAME_TAG", "Reward ID: " + (reward.isBlank() ? "Any" : reward), () -> ask(audience, "Enter the reward ID used by your rule, or a single dash for any.", text -> testSample(audience, event, name, amount, tier, combo, blank(text)), () -> testSample(audience, event, name, amount, tier, combo, reward))));
        buttons.add(button(22, "PAPER", "Preview matching rules", () -> service.dryRun(audience, SubbyService.sample(event.id, name, amount, reward, tier, combo)), "No effects are run. No names are saved."));
        buttons.add(button(24, "LIME_DYE", "Run optional test effects", () -> confirm(audience, "Run optional test effects?", () -> { service.runTest(audience, SubbyService.sample(event.id, name, amount, reward, tier, combo)); testSample(audience, event, name, amount, tier, combo, reward); }, () -> testSample(audience, event, name, amount, tier, combo, reward)), "Changes your Minecraft world using the matching actions.", "Safety limits apply. Subscriber history stays unchanged."));
        buttons.add(button(31, "ARROW", "Back", () -> testEvent(audience)));
        page(audience, "Subby - Test " + event.label, 4, buttons);
    }
    private void presets(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>(); String[] names = {"book-only", "friendly", "hardcore"};
        for (int i = 0; i < names.length; i++) { String name = names[i]; buttons.add(button(10 + i * 3, "BOOK", name, () -> confirm(audience, "Apply " + name + " preset?", () -> service.importPreset(name, true, audience, () -> advanced(audience)), () -> presets(audience)), "Replaces book settings and optional rules.", "Creates a backup. Advanced stays off.")); }
        buttons.add(button(20, "PAPER", "Export current settings", () -> ask(audience, "Enter a name for the exported preset (letters, numbers, dashes).", text -> { service.exportPreset(text.trim(), audience); presets(audience); }, () -> presets(audience)), "Leaves out credentials and private user lists."));
        buttons.add(button(24, "PAPER", "Import a preset file", () -> ask(audience, "Enter the preset filename without .json from the presets folder.", text -> confirm(audience, "Replace settings with preset?", () -> service.importPreset(text.trim(), false, audience, () -> advanced(audience)), () -> presets(audience)), () -> presets(audience)), "Only files in the presets folder can be imported.", "Dangerous actions and command approval are reset."));
        buttons.add(button(31, "ARROW", "Back", () -> advanced(audience)));
        page(audience, "Subby - Presets and sharing", 4, buttons);
    }
    private void bot(Platform.Audience audience) {
        Options.Bot bot = service.options().bot;
        page(audience, "Subby - Optional Twitch chat", 4, List.of(
                toggle(10, "Chat interface", bot.enabled, () -> {
                    if (service.connection().mode().equals("direct")) { audience.message("Optional chat requires Companion mode."); return; }
                    confirm(audience, "Change optional Twitch chat?", () -> change(audience, o -> { o.bot.enabled = !o.bot.enabled; if (o.bot.enabled) o.events.add("bot"); }, () -> { audience.message("Reconnect to approve chat permissions. Replies use your broadcaster account."); bot(audience); }), () -> bot(audience));
                }, "!subby pause/resume/status/lastsub: moderators only.", "!book and !supporters: public explanations.", "Requires Advanced interactions to be on."),
                button(12, "NAME_TAG", "Feedback style: " + bot.style, () -> change(audience, o -> o.bot.style = next(List.of("silent", "cute", "professional", "chaotic", "custom"), o.bot.style), () -> bot(audience)), "Click to cycle styles.", "Silent keeps automatic feedback off."),
                button(14, "PAPER", "Custom feedback", () -> ask(audience, "Enter your chat feedback template. Use {user} and {months}.", text -> change(audience, o -> o.bot.template = text, () -> bot(audience)), () -> bot(audience)), bot.template),
                button(16, "ENDER_EYE", "Approve permissions", () -> { platform.closeMenu(audience); service.command(audience, new String[]{"connect"}); }, "Opens a new Twitch pairing link if needed."),
                button(22, "LIME_DYE", "Apply chat settings", () -> service.updateBot(audience), "Use after approving Twitch permissions."),
                button(31, "ARROW", "Back", () -> advanced(audience))));
    }
    private void bindings(Platform.Audience audience, int page) {
        List<Binding> bindings = service.bindings(); List<UiMenu.Button> buttons = new ArrayList<>();
        for (int i = page * 36; i < Math.min(bindings.size(), (page + 1) * 36); i++) {
            Binding binding = bindings.get(i); buttons.add(button(i % 36, "LECTERN", binding.name() + " - " + binding.kind(), () -> confirm(audience, "Remove this physical binding?", () -> { service.unbind(audience, binding.name()); bindings(audience, page); }, () -> bindings(audience, page)), "Mode: " + binding.mode(), binding.x() + ", " + binding.y() + ", " + binding.z(), "Click to unbind. Existing blocks and items stay."));
        }
        if (page > 0) buttons.add(button(45, "ARROW", "Previous", () -> bindings(audience, page - 1)));
        buttons.add(button(46, "LIME_DYE", "Bind a location", () -> chooseBinding(audience), "Look at the block you want to bind first."));
        buttons.add(button(49, "ARROW", "Back", () -> advanced(audience)));
        if ((page + 1) * 36 < bindings.size()) buttons.add(button(53, "ARROW", "Next", () -> bindings(audience, page + 1)));
        page(audience, "Subby - Physical locations", 6, buttons);
    }
    private void chooseBinding(Platform.Audience audience) {
        List<UiMenu.Button> buttons = new ArrayList<>(); String[] kinds = {"archive", "live_chest", "chest", "sign", "display", "redstone"};
        for (int i = 0; i < kinds.length; i++) {
            String kind = kinds[i]; String label = switch (kind) { case "archive" -> "Archive chest"; case "live_chest" -> "Live book chest"; case "chest" -> "Items and keepsakes chest"; case "sign" -> "Subscriber sign"; case "display" -> "Floating text"; default -> "Button pulse"; };
            buttons.add(button(10 + i, "LECTERN", label, () -> {
                if (kind.equals("sign") || kind.equals("display")) boardMode(audience, kind);
                else bindPrompt(audience, kind, "manual");
            }, switch (kind) { case "archive" -> "Chest/barrel keeps copies of completed volumes."; case "live_chest" -> "Chest/barrel holds your automatically updated live book."; case "chest" -> "Rules can put items in empty chest/barrel slots."; case "redstone" -> "Rules can press this button. Requires safety approval."; case "display" -> "Creates floating text above the block."; default -> "Updates the front of a sign. Existing text is replaced."; }));
        }
        buttons.add(button(22, "ARROW", "Back", () -> bindings(audience, 0)));
        page(audience, "Subby - Bind a location", 3, buttons);
    }
    private void boardMode(Platform.Audience audience, String kind) {
        List<UiMenu.Button> buttons = new ArrayList<>(); String[] modes = {"latest", "recent", "recent_events", "wall", "top_gifters", "top_bits", "manual"};
        for (int i = 0; i < modes.length; i++) { String mode = modes[i]; buttons.add(button(10 + i, "PAPER", mode, () -> bindPrompt(audience, kind, mode), mode.equals("manual") ? "Rules set the text. No automatic board updates." : "Updates from saved supporter events.", "Names use your moderation settings.")); }
        buttons.add(button(22, "ARROW", "Back", () -> chooseBinding(audience)));
        page(audience, "Subby - Board content", 3, buttons);
    }
    private void bindPrompt(Platform.Audience audience, String kind, String mode) {
        ask(audience, "Enter a name for this binding (lowercase letters, numbers, dashes). Keep looking at its block.", text -> { service.bind(audience, text.trim(), kind, mode); bindings(audience, 0); }, () -> bindings(audience, 0));
    }
    public void confirm(Platform.Audience audience, String title, Runnable confirm, Runnable cancel) {
        page(audience, title, 3, List.of(button(11, "LIME_DYE", "Yes, continue", confirm), button(15, "GRAY_DYE", "Cancel", cancel)));
    }
    private void change(Platform.Audience audience, Consumer<Options> edit, Runnable done) { service.saveOptions(edit, audience, done); }
    private int number(String text, int min, int max) {
        try { int value = Integer.parseInt(text.trim()); if (value >= min && value <= max) return value; } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("Enter a whole number between " + min + " and " + max + ".");
    }
    public void ask(Platform.Audience audience, String question, Consumer<String> save, Runnable cancel) {
        platform.closeMenu(audience);
        prompts.put(audience.id(), new Prompt(System.currentTimeMillis() + 180000, save, cancel, audience, pagePermissions.getOrDefault(audience.id(), "subby.configure")));
        audience.message(question);
        audience.message("Type your answer in chat. It will not be broadcast to other players. Type cancel to go back.");
    }
    public boolean captureChat(String id, String text) {
        Prompt prompt = prompts.remove(id);
        if (prompt == null) return false;
        platform.execute(() -> {
            if (!prompt.audience.permitted("subby.use") && !prompt.audience.permitted("subby.admin")) { prompt.audience.message("You no longer have permission to manage Subby."); return; }
            if (!prompt.audience.permitted(prompt.permission)) { prompt.audience.message("You no longer have permission for this edit."); return; }
            if (text.equalsIgnoreCase("cancel") || System.currentTimeMillis() > prompt.expires) { prompt.cancel.run(); return; }
            try { prompt.save.accept(text); }
            catch (IllegalArgumentException e) { prompt.audience.message(e.getMessage()); ask(prompt.audience, "Please try again.", prompt.save, prompt.cancel); }
        });
        return true;
    }
    public void cancelInput(String id) { prompts.remove(id); sessions.remove(id); pagePermissions.remove(id); }
}
