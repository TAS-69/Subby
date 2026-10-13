package dev.subby.integration;

import dev.subby.core.*;
import dev.subby.minecraft.MinecraftPlatform;
import dev.subby.minecraft.Compat;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;


public final class BookSmoke {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();


        try {
            Object holder = Items.WRITTEN_BOOK.builtInRegistryHolder();
            holder.getClass().getMethod("bindComponents", DataComponentMap.class).invoke(holder,
                    DataComponentMap.builder().set(DataComponents.MAX_STACK_SIZE, 16).build());
        } catch (NoSuchMethodException ignored) {   }
        List<Subscriber> events = new ArrayList<>();
        for (int i = 0; i < 401; i++) events.add(new Subscriber("event" + i, "123", "u" + i, "Subscriber" + i, "sub", "1000", 1, "now"));
        Book book = Book.create("smoke-library", events, 0);
        MinecraftPlatform platform = new MinecraftPlatform(null);
        Method itemMethod = MinecraftPlatform.class.getDeclaredMethod("item", Book.class, boolean.class);
        itemMethod.setAccessible(true);
        ItemStack live = (ItemStack) itemMethod.invoke(platform, book, true);
        ItemStack archive = (ItemStack) itemMethod.invoke(platform, Book.create("smoke-library", events, 1), false);
        if (!live.is(Items.WRITTEN_BOOK) || live.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().size() != 1)
            throw new AssertionError("Latest volume was not serialized as a written book");
        if (archive.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().size() != 100)
            throw new AssertionError("Archive must contain 100 Minecraft pages");
        if (live.get(DataComponents.CUSTOM_DATA) == null || archive.get(DataComponents.CUSTOM_DATA) != null)
            throw new AssertionError("Only the live book must carry the library marker");
        var ops = RegistryOps.create(JsonOps.INSTANCE, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        var encoded = ItemStack.CODEC.encodeStart(ops, live).getOrThrow();
        ItemStack decoded = ItemStack.CODEC.parse(ops, encoded).getOrThrow();
        if (!live.get(DataComponents.WRITTEN_BOOK_CONTENT).equals(decoded.get(DataComponents.WRITTEN_BOOK_CONTENT)))
            throw new AssertionError("Written pages did not survive Minecraft codec serialization");
        var sign = new net.minecraft.world.level.block.entity.SignBlockEntity(net.minecraft.core.BlockPos.ZERO, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState());
        try { Compat.sign(sign, "Latest subscriber\nViewer"); }
        catch (NullPointerException e) { if (sign.getLevel() != null) throw e; }
        if (!Compat.signLines(sign).equals(List.of("Latest subscriber", "Viewer", "", ""))) throw new AssertionError("Sign board lines did not survive native components");
        var textTag = Compat.parseNbt("{text:" + Compat.textTag("Viewer \"quote\" \\ path\nNext line") + "}");
        if (!textTag.contains("text")) throw new AssertionError("Text display NBT was not parsed");
        Options rich = new Options(); rich.book.clickableNames = true; rich.book.colour = "dark_green";
        Book coloured = Book.create("rich-library", List.of(new Subscriber("rich", "123", "user", "Viewer", "sub", "1000", 1, "now", Map.of("login", "viewer"))), 0, rich);
        ItemStack colouredItem = (ItemStack) itemMethod.invoke(platform, coloured, false);
        var richEncoded = ItemStack.CODEC.encodeStart(ops, colouredItem).getOrThrow();
        if (!richEncoded.toString().contains("https://www.twitch.tv/viewer")) throw new AssertionError("Clickable book name was not encoded");
        System.out.println("SUBBY SMOKE PASSED: books, archive pages, markers, clickable names, signs and text display NBT verified");
    }
}
