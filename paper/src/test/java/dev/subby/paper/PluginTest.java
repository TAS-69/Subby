package dev.subby.paper;

import dev.subby.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import static org.junit.jupiter.api.Assertions.*;

class PluginTest {
    @TempDir Path worldFolder;
    private ServerMock server;
    private SubbyPaper plugin;
    @BeforeEach void setup() {
        server = MockBukkit.mock();
        server.addWorld(new org.mockbukkit.mockbukkit.world.WorldMock() {
            @Override public java.io.File getWorldFolder() { return worldFolder.toFile(); }
        });
        plugin = MockBukkit.load(SubbyPaper.class);
        assertTrue(plugin.isEnabled());
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }

    @Test void operatorsReceiveLiveBooksAndUnprivilegedPlayersCannotManageSubby() {
        PlayerMock viewer = server.addPlayer();
        viewer.setOp(false);
        server.dispatchCommand(viewer, "subby book");
        assertNull(viewer.getInventory().getItem(0));
        viewer.setOp(true);
        server.dispatchCommand(viewer, "subby book");
        ItemStack book = viewer.getInventory().getItem(0);
        assertNotNull(book);
        assertEquals(Material.WRITTEN_BOOK, book.getType());
        assertTrue(((BookMeta) book.getItemMeta()).getPage(1).contains("Your subscribers"));
    }
    @Test void updatesPreserveCustomItemMetadataAndNeverOverwriteOrdinaryBooks() {
        PlayerMock player = server.addPlayer(); player.setOp(true);
        server.dispatchCommand(player, "subby book");
        ItemStack live = player.getInventory().getItem(0);
        BookMeta meta = (BookMeta) live.getItemMeta();
        String library = meta.getPersistentDataContainer().get(new NamespacedKey("subby", "library"), org.bukkit.persistence.PersistentDataType.STRING);
        meta.setLore(List.of("Custom dedication")); live.setItemMeta(meta);
        ItemStack ordinary = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta ordinaryMeta = (BookMeta) ordinary.getItemMeta(); ordinaryMeta.setTitle("My diary"); ordinaryMeta.setAuthor("Me"); ordinaryMeta.setPages("Keep me"); ordinary.setItemMeta(ordinaryMeta);
        player.getInventory().setItem(1, ordinary);
        Subscriber event = new Subscriber("test", "123", "user", "Example", "sub", "1000", 1, "now");
        plugin.refresh(Book.create(library, List.of(event), 0), List.of());
        BookMeta updated = (BookMeta) player.getInventory().getItem(0).getItemMeta();
        assertTrue(updated.getPage(1).contains("Example"));
        assertEquals(List.of("Custom dedication"), updated.getLore());
        assertEquals(ordinary, player.getInventory().getItem(1));
    }
    private InventoryClickEvent click(PlayerMock player, int slot, ClickType type) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot, type, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event); server.getScheduler().performOneTick(); return event;
    }
    @Test void visualMenuCannotBeLootedAndPermissionRevocationStopsFurtherClicks() {
        PlayerMock player = server.addPlayer(); player.setOp(true); server.dispatchCommand(player, "subby");
        assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
        assertNotNull(player.getOpenInventory().getTopInventory().getItem(13));
        assertTrue(click(player, 13, ClickType.SHIFT_LEFT).isCancelled()); assertNull(player.getInventory().getItem(0));
        assertTrue(click(player, 29, ClickType.LEFT).isCancelled()); assertTrue(player.getOpenInventory().getTitle().contains("Names in your book"));
        click(player, 31, ClickType.LEFT); player.setOp(false); click(player, 13, ClickType.LEFT);
        assertNull(player.getInventory().getItem(0));
    }
    @Test void visualRuleEditorCapturesTextPrivatelyAndSavesAnOptionalRule() throws Exception {
        PlayerMock player = server.addPlayer(); player.setOp(true); server.dispatchCommand(player, "subby");
        click(player, 49, ClickType.LEFT); assertTrue(player.getOpenInventory().getTitle().contains("Advanced"), player.getOpenInventory().getTitle());
        click(player, 11, ClickType.LEFT); assertTrue(player.getOpenInventory().getTitle().contains("optional rules"), player.getOpenInventory().getTitle());
        click(player, 46, ClickType.LEFT); assertTrue(player.getOpenInventory().getTitle().contains("WHEN"), player.getOpenInventory().getTitle());
        click(player, 0, ClickType.LEFT);
        Path profileSettings = worldFolder.resolve("subby/profiles/default/settings");
        Path rulesFile = profileSettings.resolve("rules.json");
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (!player.getOpenInventory().getTitle().contains("Edit optional rule") && System.nanoTime() < end) { Thread.sleep(10); server.getScheduler().performOneTick(); }
        server.getScheduler().performOneTick(); assertTrue(player.getOpenInventory().getTitle().contains("Edit optional rule"), player.getOpenInventory().getTitle());
        click(player, 45, ClickType.LEFT); click(player, 0, ClickType.LEFT);
        AsyncPlayerChatEvent chat = new AsyncPlayerChatEvent(false, player, "Thanks {user}!", new HashSet<>(server.getOnlinePlayers()));
        server.getPluginManager().callEvent(chat); assertTrue(chat.isCancelled()); server.getScheduler().performOneTick();
        end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (JsonFiles.read(rulesFile, Rules.class, Rules.defaults()).rules().getFirst().actions().isEmpty() && System.nanoTime() < end) { Thread.sleep(10); server.getScheduler().performOneTick(); }
        Rules saved = JsonFiles.read(rulesFile, Rules.class, Rules.defaults());
        List<String> messages = new ArrayList<>(); String message;
        while ((message = player.nextMessage()) != null) messages.add(message);
        assertFalse(saved.rules().getFirst().actions().isEmpty(), String.join(" | ", messages));
        assertEquals("Thanks {user}!", saved.rules().getFirst().actions().getFirst().value());
        assertFalse(JsonFiles.read(profileSettings.resolve("options.json"), Options.class, null).advancedEnabled);
    }
    @Test void archiveChestsPreserveContentsDeduplicateVolumesAndRestoreMissingCopies() {
        World world = server.getWorlds().getFirst(); world.getChunkAt(0, 0).load();
        var block = world.getBlockAt(2, 64, 2); block.setType(Material.CHEST);
        var chest = (org.bukkit.block.Chest) block.getState(); chest.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        Binding binding = new Binding("library", "archive", world.getUID().toString(), 2, 64, 2, "manual");
        Book book = Book.create("library-id", List.of(new Subscriber("event", "123", "user", "Viewer", "sub", "1000", 1, "now")), 0);
        assertTrue(plugin.depositArchive(binding, book)); assertTrue(plugin.depositArchive(binding, book));
        assertEquals(new ItemStack(Material.DIAMOND, 4), chest.getInventory().getItem(0));
        assertEquals(1, Arrays.stream(chest.getInventory().getContents()).filter(i -> i != null && i.getType() == Material.WRITTEN_BOOK).count());
        chest.getInventory().setItem(1, null); assertTrue(plugin.depositArchive(binding, book));
        assertEquals(Material.WRITTEN_BOOK, chest.getInventory().getItem(1).getType());
        block.setType(Material.FURNACE); assertFalse(plugin.depositArchive(binding, book));
    }
    @Test void liveChestsUpdateTheirOwnBookAndPlayerBindingsLimitInventoryRefresh() {
        World world = server.getWorlds().getFirst(); world.getChunkAt(0, 0).load();
        var block = world.getBlockAt(3, 64, 3); block.setType(Material.BARREL); var barrel = (org.bukkit.block.Barrel) block.getState();
        barrel.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        Binding binding = new Binding("current", "live_chest", world.getUID().toString(), 3, 64, 3, "manual");
        Book initial = Book.create("library", List.of(), 0), updated = Book.create("library", List.of(new Subscriber("id", "123", "viewer", "Viewer", "sub", "1000", 1, "now")), 0);
        assertTrue(plugin.liveChest(binding, initial)); assertTrue(plugin.liveChest(binding, updated));
        assertEquals(Material.DIAMOND, barrel.getInventory().getItem(0).getType()); assertTrue(((BookMeta) barrel.getInventory().getItem(1).getItemMeta()).getPage(1).contains("Viewer"));
        PlayerMock first = server.addPlayer(), second = server.addPlayer(); first.setOp(true); second.setOp(true);
        server.dispatchCommand(first, "subby book"); server.dispatchCommand(second, "subby book");
        BookMeta meta = (BookMeta) first.getInventory().getItem(0).getItemMeta(); String library = meta.getPersistentDataContainer().get(new NamespacedKey("subby", "library"), org.bukkit.persistence.PersistentDataType.STRING);
        Book profileBook = Book.create(library, List.of(new Subscriber("another", "123", "viewer", "Viewer", "sub", "1000", 1, "now")), 0);
        plugin.refresh(profileBook, List.of(), first.getName());
        assertTrue(((BookMeta) first.getInventory().getItem(0).getItemMeta()).getPage(1).contains("Viewer"));
        assertFalse(((BookMeta) second.getInventory().getItem(0).getItemMeta()).getPage(1).contains("Viewer"));
    }
}
