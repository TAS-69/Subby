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

public class SubbyPaper extends JavaPlugin implements Platform {
    private SubbyService service;
    private final NamespacedKey marker = new NamespacedKey("subby", "library");
    private record Sender(CommandSender sender) implements Audience {
        @Override public void message(String message) { sender.sendMessage("[Subby] " + message); }
    }
    @Override public void onEnable() {
        try {
            service = new SubbyService(getDataFolder().toPath(), Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("subby"), this);
            Objects.requireNonNull(getCommand("subby")).setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("subby.admin")) { sender.sendMessage("You do not have permission to manage Subby."); return true; }
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
    private Player player(Audience audience) {
        if (!(audience instanceof Sender source) || !(source.sender instanceof Player player)) throw new IllegalArgumentException("Run this command as a player.");
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
        meta.setTitle(book.title()); meta.setAuthor(book.author()); meta.setPages(book.pages());
        if (live) meta.getPersistentDataContainer().set(marker, PersistentDataType.STRING, book.libraryId());
        item.setItemMeta(meta); return item;
    }
    @SuppressWarnings("deprecation")
    private ItemStack update(ItemStack existing, Book book) {
        ItemStack result = existing.clone();
        BookMeta meta = (BookMeta) result.getItemMeta();
        meta.setTitle(book.title()); meta.setAuthor(book.author()); meta.setPages(book.pages());
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
        ItemStack updated = item(book, true);
        for (Player player : Bukkit.getOnlinePlayers()) {
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
    @Override public void action(Rules.Action action, Subscriber event) {
        if (action.type().equals("message")) Bukkit.broadcastMessage(action.message(event));
        else Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.type().equals("function") ? "function " + action.value() :
                "execute as @a at @s run playsound " + action.value() + " master @s ~ ~ ~ 0.6 1");
    }
}
