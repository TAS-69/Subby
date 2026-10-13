package dev.subby.core;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PresetRegistryTest {
    @TempDir Path directory;
    @Test void importedPresetsAreOptInAndCannotBringCredentialsOrCommandApproval() throws Exception {
        Options options = new Options(); options.advancedEnabled = true; options.targetPlayer = "Creator"; options.safety.destructiveActions = true; options.safety.allowedCommands.add("weather clear"); options.moderation.blockedUsers.add("PrivateUser");
        Presets presets = new Presets(directory); presets.export("share", options, Rules.defaults());
        String exported = Files.readString(directory.resolve("presets/share.json")); assertFalse(exported.contains("PrivateUser")); assertFalse(exported.contains("Creator"));
        Options imported = presets.read("share").options(); assertFalse(imported.advancedEnabled); assertFalse(imported.safety.destructiveActions); assertTrue(imported.safety.allowedCommands.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> presets.read("../../credentials"));
    }
    @Test void supporterUserIdsTrackRenamesGiftGivingAndModeratorChanges() {
        SupporterRegistry registry = new SupporterRegistry();
        registry.record(new Subscriber("one", "123", "456", "OldName", "resub", "2000", 12, "2026-10-01T10:00:00Z"));
        registry.record(new Subscriber("two", "123", "456", "NewName", "gift_batch", "1000", 1, "2026-10-02T10:00:00Z", Map.of("login", "newname", "gift_count", "5")));
        registry.record(new Subscriber("three", "123", "456", "NewName", "moderator_add", "1000", 1, "2026-10-03T10:00:00Z"));
        var supporter = registry.find("newname").orElseThrow(); assertEquals(1, registry.all().size()); assertEquals(5, supporter.giftsGiven()); assertEquals(12, supporter.months()); assertEquals("2000", supporter.tier()); assertTrue(supporter.moderator());
    }
}
