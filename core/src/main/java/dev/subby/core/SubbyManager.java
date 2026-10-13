package dev.subby.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.subby.core.UiMenu.button;

public final class SubbyManager implements AutoCloseable {
    private record Profile(Map<String, Set<String>> owners) { Profile { owners = Map.copyOf(owners); } }
    private record Manifest(Map<String, Profile> profiles) { Manifest { profiles = Map.copyOf(profiles); } }
    private record Prompt(Platform.Audience audience, long expires) {}
    private static final Set<String> RIGHTS = Set.of("book", "connect", "appearance", "rules", "bind", "moderate", "configure", "test", "pause");
    private final Path settings, world, manifestFile;
    private final Platform platform;
    private volatile Manifest manifest;
    private final Map<String, SubbyService> services = new ConcurrentHashMap<>();
    private final Map<String, String> selections = new HashMap<>();
    private final Map<String, Prompt> prompts = new ConcurrentHashMap<>();
    public SubbyManager(Path settings, Path world, Platform platform) throws IOException {
        this.settings = settings; this.world = world; this.platform = platform; manifestFile = world.resolve("profiles.json");
        manifest = JsonFiles.read(manifestFile, Manifest.class, new Manifest(Map.of("default", new Profile(Map.of()))));
        if (!manifest.profiles.containsKey("default") || manifest.profiles.size() > 32) throw new IOException("Invalid profile list; existing files were preserved.");
        for (String name : manifest.profiles.keySet()) { validateName(name); services.put(name, open(name)); }
        if (!Files.exists(manifestFile)) JsonFiles.write(manifestFile, manifest);
    }
    private SubbyService open(String name) throws IOException {
        Path folder = world.resolve("profiles").resolve(name);
        Path profileSettings = folder.resolve("settings");
        if (name.equals("default")) for (String file : List.of("config.json", "rules.json", "options.json", "connection.json", "credentials.json", "companion-credentials.json")) {
            Path destination = profileSettings.resolve(file), legacy = settings.resolve(file);
            if (Files.exists(legacy) && !Files.exists(destination)) { Files.createDirectories(profileSettings); Files.copy(legacy, destination); }
        }
        if (!name.equals("default") && !Files.exists(profileSettings.resolve("connection.json")))
            {
                ConnectionSettings source = services.containsKey("default") ? services.get("default").connection() : JsonFiles.read(settings.resolve("connection.json"), ConnectionSettings.class, ConnectionSettings.defaults());
                JsonFiles.write(profileSettings.resolve("connection.json"), new ConnectionSettings(source.mode(), source.serviceUrl(), name, source.autoConnect()));
            }
        return new SubbyService(profileSettings, name.equals("default") ? world : folder.resolve("library"), new ProfilePlatform(name));
    }
    private static void validateName(String name) { if (name == null || !name.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Use 1–32 lowercase letters, numbers, dashes, or underscores for a profile name."); }
    private boolean owns(Platform.Audience audience, String name, String permission) {
        if (audience.permitted("subby.admin")) return true;
        Set<String> rights = manifest.profiles.getOrDefault(name, new Profile(Map.of())).owners.getOrDefault(audience.id(), Set.of());
        return rights.contains(permission.replace("subby.", ""));
    }
    private boolean accessible(Platform.Audience audience, String name) {
        return audience.permitted("subby.admin") || manifest.profiles.get(name).owners.containsKey(audience.id()) || name.equals("default") && audience.permitted("subby.book");
    }
    public boolean allowed(Platform.Audience audience) { return manifest.profiles.keySet().stream().anyMatch(name -> accessible(audience, name)); }
    private String selected(Platform.Audience audience) {
        String name = selections.getOrDefault(audience.id(), "default");
        if (accessible(audience, name)) return name;
        return manifest.profiles.keySet().stream().filter(n -> accessible(audience, n)).sorted().findFirst().orElse("default");
    }
    private Platform.Audience scoped(Platform.Audience original, String profile) {
        return new Platform.Audience() {
            public void message(String message) { original.message(message); }
            @Override public String id() { return original.id(); }
            @Override public String name() { return original.name(); }
            @Override public boolean player() { return original.player(); }
            @Override public Platform.Audience unwrap() { return original.unwrap(); }
            @Override public void link(String label, String url) { original.link(label, url); }
            @Override public boolean permitted(String permission) {
                if (!accessible(original, profile)) return false;
                if (permission.equals("subby.use")) return true;
                return owns(original, profile, permission) || profile.equals("default") && original.permitted(permission);
            }
        };
    }
    public void command(Platform.Audience audience, String[] args) {
        try {
            if (!allowed(audience)) { audience.message("You do not have permission to use a Subby profile."); return; }
            if (args.length > 0 && Set.of("profile", "profiles").contains(args[0])) {
                if (args.length == 1) { menu(audience); return; }
                switch (args[1]) {
                    case "switch" -> { if (args.length != 3) throw new IllegalArgumentException("Usage: /subby profile switch <name>"); select(audience, args[2]); }
                    case "create" -> { if (args.length != 3) throw new IllegalArgumentException("Usage: /subby profile create <name>"); create(audience, args[2]); }
                    case "grant" -> {
                        if (!audience.permitted("subby.admin")) throw new IllegalArgumentException("Only a server administrator can grant profile access.");
                        if (args.length < 4 || args.length > 5) throw new IllegalArgumentException("Usage: /subby profile grant <profile> <player UUID> [book,connect,appearance,rules,bind,moderate,configure,test,pause]");
                        UUID.fromString(args[3]); Profile existing = manifest.profiles.get(args[2]); if (existing == null) throw new IllegalArgumentException("Profile not found.");
                        Set<String> rights = args.length == 5 ? Set.of(args[4].split(",")) : RIGHTS;
                        if (rights.isEmpty() || !RIGHTS.containsAll(rights)) throw new IllegalArgumentException("Choose supported profile permissions.");
                        Map<String, Set<String>> owners = new HashMap<>(existing.owners); owners.put(args[3], rights); save(args[2], new Profile(owners)); audience.message("Profile access saved.");
                    }
                    case "revoke" -> {
                        if (!audience.permitted("subby.admin") || args.length != 4) throw new IllegalArgumentException("Administrator usage: /subby profile revoke <profile> <player UUID>");
                        Profile existing = manifest.profiles.get(args[2]); if (existing == null) throw new IllegalArgumentException("Profile not found.");
                        Map<String, Set<String>> owners = new HashMap<>(existing.owners); owners.remove(args[3]); save(args[2], new Profile(owners)); audience.message("Profile access revoked.");
                    }
                    default -> audience.message("Use profile switch, create, grant, or revoke.");
                }
            } else {
                String name = selected(audience);
                services.get(name).command(scoped(audience, name), args);
            }
        } catch (IOException | IllegalArgumentException e) { audience.message("Could not update profile: " + e.getMessage()); }
    }
    private void save(String name, Profile profile) throws IOException {
        Map<String, Profile> profiles = new HashMap<>(manifest.profiles); profiles.put(name, profile);
        Manifest replacement = new Manifest(profiles); JsonFiles.write(manifestFile, replacement); manifest = replacement;
    }
    private void create(Platform.Audience audience, String name) throws IOException {
        if (!audience.permitted("subby.admin")) throw new IllegalArgumentException("Only a server administrator can create profiles.");
        validateName(name); if (manifest.profiles.containsKey(name) || manifest.profiles.size() >= 32) throw new IllegalArgumentException("Profile already exists or the 32 profile limit was reached.");
        SubbyService service = open(name);
        try { save(name, new Profile(Map.of())); services.put(name, service); }
        catch (IOException e) { service.close(); throw e; }
        select(audience, name);
    }
    private void select(Platform.Audience audience, String name) {
        if (!manifest.profiles.containsKey(name) || !accessible(audience, name)) throw new IllegalArgumentException("Profile not found or access not granted.");
        services.values().forEach(service -> service.cancelInput(audience.id()));
        selections.put(audience.id(), name); audience.message("Active profile: " + name + ". Each profile has its own Twitch sign-in, book library, and optional rules.");
        services.get(name).command(scoped(audience, name), new String[0]);
    }
    private void menu(Platform.Audience audience) {
        if (!audience.player()) { audience.message("Profiles: " + String.join(", ", manifest.profiles.keySet()) + ". Use /subby profile switch <name>."); return; }
        List<UiMenu.Button> buttons = new ArrayList<>(); List<String> names = manifest.profiles.keySet().stream().filter(name -> accessible(audience, name)).sorted().toList();
        for (int i = 0; i < names.size(); i++) { String name = names.get(i); buttons.add(button(i, "BOOK", name + (name.equals(selected(audience)) ? " (selected)" : ""), () -> { try { select(audience, name); } catch (IllegalArgumentException e) { audience.message(e.getMessage()); } }, services.get(name).connectionStatus(), "Click to manage this separate book library.")); }
        if (audience.permitted("subby.admin")) buttons.add(button(45, "LIME_DYE", "Create profile", () -> { platform.closeMenu(audience); prompts.put(audience.id(), new Prompt(audience, System.currentTimeMillis() + 180000)); audience.message("Type a new profile name in chat, or cancel. It is not broadcast."); }, "For another creator or an independent setup."));
        buttons.add(button(49, "ARROW", "Back", () -> services.get(selected(audience)).command(scoped(audience, selected(audience)), new String[0])));
        platform.openMenu(audience, new UiMenu("Subby - Profiles", 6, buttons));
    }
    public boolean captureChat(String id, String text) {
        Prompt prompt = prompts.remove(id);
        if (prompt != null) {
            platform.execute(() -> { if (System.currentTimeMillis() > prompt.expires || text.equalsIgnoreCase("cancel")) { menu(prompt.audience); return; } try { create(prompt.audience, text.trim()); } catch (IOException | IllegalArgumentException e) { prompt.audience.message(e.getMessage()); menu(prompt.audience); } }); return true;
        }
        for (SubbyService service : services.values()) if (service.captureChat(id, text)) return true;
        return false;
    }
    public void tick() { services.values().forEach(SubbyService::tick); }
    @Override public void close() { services.values().forEach(SubbyService::close); }
    private final class ProfilePlatform implements Platform {
        private final String profile;
        ProfilePlatform(String profile) { this.profile = profile; }
        public void execute(Runnable action) { platform.execute(action); }
        public void log(String message) { platform.log("[" + profile + "] " + message); }
        public void give(Audience audience, Book book, boolean live) { platform.give(audience, book, live); }
        public LecternTarget targetedLectern(Audience audience) {
            LecternTarget target = platform.targetedLectern(audience);
            for (var entry : services.entrySet()) if (!entry.getKey().equals(profile) && entry.getValue().boundLecterns().contains(target)) throw new IllegalArgumentException("That lectern belongs to another Subby profile.");
            return target;
        }
        public void refresh(Book book, List<LecternTarget> lecterns) { platform.refresh(book, lecterns); }
        public void refresh(Book book, List<LecternTarget> lecterns, String player) { platform.refresh(book, lecterns, player); }
        public void announce(String name) { platform.announce(name); }
        public void action(Rules.Action action, Subscriber event) { platform.action(action, event); }
        public void action(Rules.Action action, Subscriber event, String target) { platform.action(action, event, target); }
        public PlayerState playerState(String name) { return platform.playerState(name); }
        public List<String> players() { return platform.players(); }
        public void openMenu(Audience audience, UiMenu menu) { platform.openMenu(audience, menu); }
        public void closeMenu(Audience audience) { platform.closeMenu(audience); }
        public void profiles(Audience audience) { SubbyManager.this.menu(audience.unwrap()); }
        public Binding targetedBinding(Audience audience, String name, String kind, String mode) {
            Binding target = platform.targetedBinding(audience, name, kind, mode);
            for (var entry : services.entrySet()) if (!entry.getKey().equals(profile)) for (Binding binding : entry.getValue().bindings())
                if (binding.world().equals(target.world()) && binding.x() == target.x() && binding.y() == target.y() && binding.z() == target.z()) throw new IllegalArgumentException("That location belongs to another Subby profile.");
            return target;
        }
        public boolean depositArchive(Binding binding, Book book) { return platform.depositArchive(binding, book); }
        public boolean depositItem(Binding binding, String item, int amount) { return platform.depositItem(binding, item, amount); }
        public void board(Binding binding, String text, String library) { platform.board(binding, text, library); }
        public boolean pulse(Binding binding, boolean powered) { return platform.pulse(binding, powered); }
        public void giveNamed(String name, Book book) { platform.giveNamed(name, book); }
        public void giveNamed(String name, Book book, boolean live) { platform.giveNamed(name, book, live); }
        public boolean liveChest(Binding binding, Book book) { return platform.liveChest(binding, book); }
        public boolean nameFilterAvailable(String player) { return platform.nameFilterAvailable(player); }
        public java.util.concurrent.CompletableFuture<String> filterName(String name, String player) { return platform.filterName(name, player); }
    }
}
