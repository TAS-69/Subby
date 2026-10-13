package dev.subby.paper;

import dev.subby.core.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
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
}
