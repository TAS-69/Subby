package dev.subby.paper;

import dev.subby.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.*;

public class SubbyPaper extends JavaPlugin implements Platform, Listener {
    private SubbyManager service;
    private final NamespacedKey marker = new NamespacedKey("subby", "library");
    private record Sender(CommandSender sender) implements Audience {
        @Override public void message(String message) { sender.sendMessage("[Subby] " + message); }
        @Override public String id() { return sender instanceof Player p ? p.getUniqueId().toString() : "console"; }
        @Override public String name() { return sender.getName(); }
        @Override public boolean player() { return sender instanceof Player; }
        @Override public boolean permitted(String permission) { return sender.hasPermission(permission); }
        @Override public void link(String label, String url) { sender.sendMessage(Component.text(label, NamedTextColor.GREEN).decorate(TextDecoration.UNDERLINED).clickEvent(ClickEvent.openUrl(url))); }
    }
    private static final class MenuHolder implements InventoryHolder {
        private final UiMenu menu;
        private final UUID owner;
        private Inventory inventory;
        MenuHolder(UiMenu menu, UUID owner) { this.menu = menu; this.owner = owner; }
        @Override public Inventory getInventory() { return inventory; }
    }
    @Override public void onEnable() {
        try {
            service = new SubbyManager(getDataFolder().toPath(), Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("subby"), this);
            Bukkit.getPluginManager().registerEvents(this, this);
            Objects.requireNonNull(getCommand("subby")).setExecutor((sender, command, label, args) -> {
                if (!service.allowed(new Sender(sender))) { sender.sendMessage("You do not have permission to manage Subby."); return true; }
                service.command(new Sender(sender), args); return true;
            });
            Objects.requireNonNull(getCommand("subby")).setTabCompleter((sender, command, label, args) -> {
                if (!sender.hasPermission("subby.admin")) return List.of();
                if (args.length == 1) return List.of("setup", "login", "connect", "disconnect", "logout", "status", "book", "lectern", "test", "reload", "pause", "resume").stream().filter(s -> s.startsWith(args[0])).toList();
                return args.length == 2 && args[0].equals("lectern") ? List.of("bind", "unbind") : List.of();
            });
            Bukkit.getScheduler().runTaskTimer(this, () -> service.tick(), 20L, 20L);
        } catch (Exception e) { getLogger().log(java.util.logging.Level.SEVERE, "Subby could not start; existing data has been preserved.", e); Bukkit.getPluginManager().disablePlugin(this); }
    }
    @Override public void onDisable() { if (service != null) { service.close(); service = null; } }
    @Override public void execute(Runnable action) { if (isEnabled()) Bukkit.getScheduler().runTask(this, action); }
    @Override public void log(String message) { getLogger().info(message); }
    @EventHandler public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        if (!holder.owner.equals(event.getWhoClicked().getUniqueId()) || event.getClick() != ClickType.LEFT || event.getRawSlot() < 0 || event.getRawSlot() >= holder.menu.rows() * 9) return;
        execute(() -> {
            if (event.getWhoClicked().getOpenInventory().getTopInventory() == holder.inventory) holder.menu.click(event.getRawSlot());
        });
    }
    @EventHandler public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) event.setCancelled(true);
    }
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST) public void onPrompt(AsyncPlayerChatEvent event) {
        if (service != null && service.captureChat(event.getPlayer().getUniqueId().toString(), event.getMessage())) event.setCancelled(true);
    }
    @Override public void openMenu(Audience audience, UiMenu menu) {
        Player player = player(audience);
        MenuHolder holder = new MenuHolder(menu, player.getUniqueId());
        holder.inventory = Bukkit.createInventory(holder, menu.rows() * 9, Component.text(menu.title()));
        for (UiMenu.Button button : menu.buttons()) {
            Material material = Material.matchMaterial(button.icon());
            ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
            var meta = item.getItemMeta();
            meta.displayName(Component.text(button.label(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(button.description().stream().map(text -> Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)).toList());
            item.setItemMeta(meta); holder.inventory.setItem(button.slot(), item);
        }
        player.openInventory(holder.inventory);
    }
    @Override public void closeMenu(Audience audience) { player(audience).closeInventory(); }
    private Block boundBlock(Binding binding) {
        World world; try { world = Bukkit.getWorld(UUID.fromString(binding.world())); } catch (IllegalArgumentException e) { return null; }
        return world == null || !world.isChunkLoaded(binding.x() >> 4, binding.z() >> 4) ? null : world.getBlockAt(binding.x(), binding.y(), binding.z());
    }
    @Override public Binding targetedBinding(Audience audience, String name, String kind, String mode) {
        Player player = player(audience); Block block = player.getTargetBlockExact(6);
        if (block == null) throw new IllegalArgumentException("Look at a block within six blocks.");
        boolean valid = switch (kind) {
            case "archive", "live_chest", "chest" -> block.getState() instanceof Chest || block.getState() instanceof Barrel;
            case "sign" -> block.getState() instanceof Sign;
            case "redstone" -> block.getType().name().endsWith("_BUTTON"); case "display" -> true; default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Look at a chest/barrel, sign, or button matching the selected binding.");
        return new Binding(name, kind, block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ(), mode);
    }
    @Override public boolean depositArchive(Binding binding, Book book) {
        Block block = boundBlock(binding); if (block == null || !(block.getState() instanceof Container container) || !(container instanceof Chest || container instanceof Barrel)) return false;
        ItemStack expected = item(book, false);
        NamespacedKey archiveKey = new NamespacedKey("subby", "archive"); String archiveId = book.libraryId() + ":" + book.volume();
        var meta = expected.getItemMeta(); meta.getPersistentDataContainer().set(archiveKey, PersistentDataType.STRING, archiveId); expected.setItemMeta(meta);
        for (ItemStack existing : container.getInventory().getContents()) if (existing != null && existing.hasItemMeta() && archiveId.equals(existing.getItemMeta().getPersistentDataContainer().get(archiveKey, PersistentDataType.STRING))) return true;
        int slot = container.getInventory().firstEmpty(); if (slot < 0) return false;
        container.getInventory().setItem(slot, expected); return true;
    }
    @Override public boolean depositItem(Binding binding, String id, int amount) {
        Block block = boundBlock(binding); if (block == null || !(block.getState() instanceof Container container) || !(container instanceof Chest || container instanceof Barrel)) return false;
        Material material = Material.matchMaterial(id); if (material == null || !material.isItem()) throw new IllegalArgumentException("Unknown item ID.");
        int slot = container.getInventory().firstEmpty(); if (slot < 0) return false;
        container.getInventory().setItem(slot, new ItemStack(material, Math.min(material.getMaxStackSize(), amount))); return true;
    }
    @SuppressWarnings("deprecation")
    @Override public void board(Binding binding, String text, String library) {
        Block block = boundBlock(binding); if (block == null) return;
        if (binding.kind().equals("sign")) {
            if (block.getState() instanceof Sign sign) {
                String[] lines = text.split("\n", -1); var side = sign.getSide(org.bukkit.block.sign.Side.FRONT);
                for (int i = 0; i < 4; i++) side.setLine(i, i < lines.length ? lines[i] : ""); sign.update();
            }
        } else {
            String tag = "subby_" + library + "_" + binding.name();
            var location = block.getLocation().add(0.5, 1.5, 0.5);
            org.bukkit.entity.TextDisplay display = block.getWorld().getNearbyEntities(location, 3, 3, 3).stream().filter(e -> e instanceof org.bukkit.entity.TextDisplay && e.getScoreboardTags().contains(tag)).map(e -> (org.bukkit.entity.TextDisplay) e).findFirst().orElseGet(() -> {
                var created = block.getWorld().spawn(location, org.bukkit.entity.TextDisplay.class); created.addScoreboardTag(tag); created.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); return created;
            });
            display.setText(text);
        }
    }
    @Override public boolean pulse(Binding binding, boolean powered) {
        Block block = boundBlock(binding); if (block == null) return false;
        if (block.getType().name().endsWith("_BUTTON") && block.getBlockData() instanceof org.bukkit.block.data.Powerable button) { button.setPowered(powered); block.setBlockData(button, true); }
        return true;
    }
    @Override public void giveNamed(String name, Book book) {
        giveNamed(name, book, false);
    }
    @Override public void giveNamed(String name, Book book, boolean live) {
        Player player = Bukkit.getPlayerExact(name); if (player == null || player.getInventory().firstEmpty() < 0) throw new IllegalArgumentException("Player is offline or inventory is full.");
        player.getInventory().addItem(item(book, live));
    }
    @Override public boolean liveChest(Binding binding, Book book) {
        Block block = boundBlock(binding); if (block == null || !(block.getState() instanceof Container container) || !(container instanceof Chest || container instanceof Barrel)) return false;
        Inventory inventory = container.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) if (live(inventory.getItem(slot), book)) { inventory.setItem(slot, update(inventory.getItem(slot), book)); return true; }
        int slot = inventory.firstEmpty(); if (slot < 0) return false; inventory.setItem(slot, item(book, true)); return true;
    }
    private List<Component> pages(Book book) {
        return book.richPages().stream().map(page -> {
            Component component = Component.empty();
            for (Book.Span span : page) {
                NamedTextColor colour = NamedTextColor.NAMES.value(span.colour());
                Component text = Component.text(span.text(), colour == null ? NamedTextColor.BLACK : colour);
                component = component.append(span.url().isEmpty() ? text : text.clickEvent(ClickEvent.openUrl(span.url())));
            }
            return component;
        }).toList();
    }
    @SuppressWarnings("deprecation")
    private void setPages(BookMeta meta, Book book) {
        boolean plain = book.richPages().stream().flatMap(List::stream).allMatch(span -> span.colour().equals("black") && span.url().isEmpty());
        if (plain) meta.setPages(book.pages()); else meta.pages(pages(book));
    }
    private Player player(Audience audience) {
        if (!(audience.unwrap() instanceof Sender source) || !(source.sender instanceof Player player)) throw new IllegalArgumentException("Run this command as a player.");
        return player;
    }
    private boolean live(ItemStack item, Book book) {
        return item != null && item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta() &&
                book.libraryId().equals(item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.STRING));
    }
    @SuppressWarnings("deprecation")
    private ItemStack item(Book book, boolean live) {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.setTitle(book.title()); meta.setAuthor(book.author()); setPages(meta, book);
        if (live) meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, book.libraryId());
        item.setItemMeta(meta); return item;
    }
    @SuppressWarnings("deprecation")
    private ItemStack update(ItemStack existing, Book book) {
        ItemStack result = existing.clone();
        BookMeta meta = (BookMeta) result.getItemMeta();
        meta.setTitle(book.title()); meta.setAuthor(book.author()); setPages(meta, book);
        result.setItemMeta(meta);
        return result;
    }
    @Override public void give(Audience audience, Book book, boolean live) {
        Player player = player(audience);
        player.getInventory().addItem(item(book, live)).values().forEach(extra -> player.getWorld().dropItemNaturally(player.getLocation(), extra));
        audience.message((live ? "Live" : "Archive") + " book created: volume " + book.volume() + ".");
    }
    @Override public LecternTarget targetedLectern(Audience audience) {
        Player player = player(audience);
        Block block = player.getTargetBlockExact(6);
        if (block == null || !(block.getState() instanceof Lectern)) throw new IllegalArgumentException("Look at a lectern within six blocks.");
        return new LecternTarget(block.getWorld().getUID().toString(), block.getX(), block.getY(), block.getZ());
    }
    @Override public void refresh(Book book, List<LecternTarget> targets) {
        refresh(book, targets, "");
    }
    @Override public void refresh(Book book, List<LecternTarget> targets, String boundPlayer) {
        ItemStack updated = item(book, true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!boundPlayer.isEmpty() && !player.getName().equalsIgnoreCase(boundPlayer)) continue;
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack existing = inventory.getItem(slot);
                if (live(existing, book)) {
                    ItemStack replacement = update(existing, book);
                    if (!replacement.equals(existing)) inventory.setItem(slot, replacement);
                }
            }
        }
        for (LecternTarget target : targets) {
            World world;
            try { world = Bukkit.getWorld(UUID.fromString(target.world())); } catch (IllegalArgumentException e) { continue; }
            if (world == null || !world.isChunkLoaded(target.x() >> 4, target.z() >> 4)) continue;
            if (!(world.getBlockAt(target.x(), target.y(), target.z()).getState() instanceof Lectern lectern)) continue;
            ItemStack existing = lectern.getInventory().getItem(0);
            if (existing == null || existing.getType().isAir() || live(existing, book)) {
                ItemStack replacement = existing == null || existing.getType().isAir() ? updated.clone() : update(existing, book);
                if (!replacement.equals(existing)) {
                    int page = lectern.getPage();
                    lectern.getInventory().setItem(0, replacement); lectern.setPage(Math.min(page, book.pages().size() - 1)); lectern.update();
                }
            }
        }
    }
    @Override public void announce(String name) { Bukkit.broadcastMessage("[Subby] " + name + " joined the subscriber book!"); }
    @Override public List<String> players() { return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(); }
    @Override public PlayerState playerState(String name) {
        Player player = Bukkit.getPlayerExact(name); if (player == null) return null;
        boolean boss = player.getNearbyEntities(128, 128, 128).stream().anyMatch(e -> e instanceof org.bukkit.entity.Wither || e instanceof org.bukkit.entity.EnderDragon);
        boss |= player.getWorld().getRaids().stream().anyMatch(raid -> raid.getStatus() == org.bukkit.Raid.RaidStatus.ONGOING && raid.getLocation().distanceSquared(player.getLocation()) <= 128 * 128);
        return new PlayerState(player.getName(), player.getWorld().getKey().toString(), player.getHealth(), player.getWorld().isHardcore(), boss);
    }
    @Override public void action(Rules.Action action, Subscriber event, String target) {
        if (action.type().equals("message")) { action(action, event); return; }
        for (String command : ActionCommands.create(action, event, target)) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
    @Override public void action(Rules.Action action, Subscriber event) {
        if (action.type().equals("message")) Bukkit.broadcastMessage(action.message(event));
        else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.type().equals("function") ? "function " + action.value() :
                "execute as @a at @s run playsound " + action.value() + " master @s ~ ~ ~ 0.6 1");
    }
}
