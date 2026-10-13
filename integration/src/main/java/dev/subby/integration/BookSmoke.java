package dev.subby.integration;

import dev.subby.core.*;
import dev.subby.minecraft.MinecraftPlatform;
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
        System.out.println("SUBBY BOOK SMOKE PASSED: live volume 2, archive 100 pages, markers and native components verified");
    }
}
