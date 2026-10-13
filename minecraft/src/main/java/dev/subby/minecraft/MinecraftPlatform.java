package dev.subby.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.subby.core.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.commands.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.*;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.phys.*;
import org.slf4j.LoggerFactory;

public final class MinecraftPlatform implements Platform {
    private final MinecraftServer server;
    public record Source(CommandSourceStack source) implements Audience {
        @Override public void message(String message) { source.sendSuccess(() -> Component.literal("[Subby] " + message), false); }
        @Override public String id() { return source.getEntity() instanceof ServerPlayer player ? player.getUUID().toString() : "console"; }
        @Override public String name() { return source.getTextName(); }
        @Override public boolean player() { return source.getEntity() instanceof ServerPlayer; }
        @Override public boolean permitted(String permission) { return Compat.allowed(source); }
        @Override public void link(String label, String url) { source.sendSuccess(() -> Component.literal(label).withStyle(s -> s.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(Compat.link(url))), false); }
    }
    public MinecraftPlatform(MinecraftServer server) { this.server = server; }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<SubbyManager> service) {
        dispatcher.register(Commands.literal("subby").requires(source -> Compat.allowed(source) || service.get() != null && service.get().allowed(new Source(source)))
                .executes(ctx -> dispatch(ctx.getSource(), service, new String[0]))
                .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(ctx ->
                        dispatch(ctx.getSource(), service, StringArgumentType.getString(ctx, "arguments").trim().split("\\s+")))));
    }
    private static int dispatch(CommandSourceStack source, Supplier<SubbyManager> service, String[] args) {
        Source audience = new Source(source);
        if (service.get() == null) audience.message("Subby is unavailable. Check the server log for the startup error.");
        else service.get().command(audience, args);
        return 1;
    }
    @Override public void execute(Runnable action) { server.execute(action); }
    @Override public void log(String message) { LoggerFactory.getLogger("Subby").info(message); }
    private ServerPlayer player(Audience audience) {
        if (!(audience.unwrap() instanceof Source source) || !(source.source.getEntity() instanceof ServerPlayer player))
            throw new IllegalArgumentException("Run this command as a player.");
        return player;
    }
    private String marker(Book book) { return "subby_live_" + book.libraryId(); }
    private boolean live(ItemStack stack, Book book) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return stack.is(Items.WRITTEN_BOOK) && data != null && data.copyTag().contains(marker(book));
    }
    private WrittenBookContent content(Book book) {
        return new WrittenBookContent(Filterable.passThrough(book.title()), book.author(), 0,
                book.richPages().stream().map(page -> {
                    var component = Component.empty();
                    for (Book.Span span : page) component.append(Component.literal(span.text()).withStyle(s -> {
                        var style = s.withColor(ChatFormatting.valueOf(span.colour().toUpperCase(Locale.ROOT)));
                        return span.url().isEmpty() ? style : style.withClickEvent(Compat.link(span.url()));
                    }));
                    return Filterable.passThrough((Component) component);
                }).toList(), true);
    }
    private Item icon(String name) {
        return switch (name) {
            case "AMETHYST_SHARD" -> Items.AMETHYST_SHARD; case "ENDER_EYE" -> Items.ENDER_EYE;
            case "WRITTEN_BOOK" -> Items.WRITTEN_BOOK; case "WRITABLE_BOOK" -> Items.WRITABLE_BOOK;
            case "LECTERN" -> Items.LECTERN; case "CLOCK" -> Items.CLOCK; case "BARRIER" -> Items.BARRIER;
            case "COMPARATOR" -> Items.COMPARATOR; case "NAME_TAG" -> Items.NAME_TAG;
            case "LIME_DYE" -> Items.EMERALD; case "GRAY_DYE" -> Items.IRON_NUGGET; case "ARROW" -> Items.ARROW;
            case "REDSTONE" -> Items.REDSTONE; case "BOOK" -> Items.BOOK; case "BELL" -> Items.BELL;
            case "COMMAND_BLOCK" -> Items.COMMAND_BLOCK; default -> Items.PAPER;
        };
    }
    @Override public void openMenu(Audience audience, UiMenu menu) {
        ServerPlayer player = player(audience);
        SimpleContainer container = new SimpleContainer(menu.rows() * 9);
        for (UiMenu.Button button : menu.buttons()) {
            ItemStack item = new ItemStack(icon(button.icon()));
            item.set(DataComponents.CUSTOM_NAME, Component.literal(button.label()).withStyle(ChatFormatting.GREEN));
            item.set(DataComponents.LORE, new ItemLore(button.description().stream().map(s -> (Component) Component.literal(s).withStyle(ChatFormatting.GRAY)).toList()));
            container.setItem(button.slot(), item);
        }
        player.openMenu(new SimpleMenuProvider((id, inventory, user) -> new SubbyChestMenu(id, inventory, container, menu), Component.literal(menu.title())));
    }
    @Override public void closeMenu(Audience audience) { player(audience).closeContainer(); }
    private ServerLevel level(Binding binding) {
        for (ServerLevel level : server.getAllLevels()) if (Compat.dimension(level).equals(binding.world()) && level.hasChunkAt(new BlockPos(binding.x(), binding.y(), binding.z()))) return level;
        return null;
    }
    @Override public Binding targetedBinding(Audience audience, String name, String kind, String mode) {
        ServerPlayer player = player(audience); HitResult hit = player.pick(6, 0, false);
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) throw new IllegalArgumentException("Look at a block within six blocks.");
        BlockPos pos = block.getBlockPos(); var entity = player.level().getBlockEntity(pos);
        boolean valid = switch (kind) {
            case "archive", "live_chest", "chest" -> entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity || entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity;
            case "sign" -> entity instanceof net.minecraft.world.level.block.entity.SignBlockEntity;
            case "redstone" -> player.level().getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.ButtonBlock;
            case "display" -> true; default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Look at a chest/barrel, sign, or button matching the selected binding.");
        return new Binding(name, kind, Compat.dimension(player), pos.getX(), pos.getY(), pos.getZ(), mode);
    }
    @Override public boolean depositArchive(Binding binding, Book book) {
        ServerLevel level = level(binding); if (level == null) return false;
        var entity = level.getBlockEntity(new BlockPos(binding.x(), binding.y(), binding.z()));
        if (!(entity instanceof net.minecraft.world.Container container) || !(entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity || entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) return false;
        String key = "subby_archive_" + book.libraryId() + "_" + book.volume();
        for (int slot = 0; slot < container.getContainerSize(); slot++) { CustomData data = container.getItem(slot).get(DataComponents.CUSTOM_DATA); if (data != null && data.copyTag().contains(key)) return true; }
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).isEmpty()) { ItemStack archive = item(book, false); CompoundTag tag = new CompoundTag(); tag.putBoolean(key, true); archive.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); container.setItem(slot, archive); container.setChanged(); return true; }
        return false;
    }
    @Override public boolean depositItem(Binding binding, String id, int amount) {
        ServerLevel level = level(binding); if (level == null) return false;
        var entity = level.getBlockEntity(new BlockPos(binding.x(), binding.y(), binding.z()));
        if (!(entity instanceof net.minecraft.world.Container container) || !(entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity || entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) return false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).isEmpty()) {
            return run("execute in " + binding.world() + " run item replace block " + binding.x() + " " + binding.y() + " " + binding.z() + " container." + slot + " with " + id + " " + amount) > 0;
        }
        return false;
    }
    private int run(String command) {
        try { return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack().withSuppressedOutput()); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalArgumentException("Minecraft rejected an optional action: " + e.getRawMessage().getString()); }
    }
    @Override public void board(Binding binding, String text, String library) {
        ServerLevel level = level(binding); if (level == null) return;
        BlockPos pos = new BlockPos(binding.x(), binding.y(), binding.z());
        if (binding.kind().equals("sign")) {
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                Compat.sign(sign, text); sign.setChanged(); level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            }
        } else {
            String tag = "subby_" + library + "_" + binding.name();
            var entities = level.getEntitiesOfClass(net.minecraft.world.entity.Display.TextDisplay.class, new AABB(pos).inflate(3), e -> Compat.hasTag(e, tag));
            if (entities.isEmpty()) run("execute in " + binding.world() + " run summon minecraft:text_display " + (binding.x() + 0.5) + " " + (binding.y() + 1.5) + " " + (binding.z() + 0.5) + " {Tags:[\"" + tag + "\"],billboard:\"center\"}");
            run("execute in " + binding.world() + " run data merge entity @e[type=minecraft:text_display,tag=" + tag + ",limit=1] {text:" + Compat.textTag(text) + "}");
        }
    }
    @Override public boolean pulse(Binding binding, boolean powered) {
        ServerLevel level = level(binding); if (level == null) return false;
        BlockPos pos = new BlockPos(binding.x(), binding.y(), binding.z()); var state = level.getBlockState(pos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.ButtonBlock button) {
            if (powered) button.press(state, level, pos, null);
            else level.scheduleTick(pos, button, 1);
        }
        return true;
    }
    @Override public void giveNamed(String name, Book book) {
        giveNamed(name, book, false);
    }
    @Override public void giveNamed(String name, Book book, boolean live) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null || !player.getInventory().add(item(book, live))) throw new IllegalArgumentException("Player is offline or inventory is full.");
    }
    @Override public boolean liveChest(Binding binding, Book book) {
        ServerLevel level = level(binding); if (level == null) return false;
        var entity = level.getBlockEntity(new BlockPos(binding.x(), binding.y(), binding.z()));
        if (!(entity instanceof net.minecraft.world.Container container) || !(entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity || entity instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity)) return false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (live(container.getItem(slot), book)) { container.getItem(slot).set(DataComponents.WRITTEN_BOOK_CONTENT, content(book)); container.setChanged(); return true; }
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).isEmpty()) { container.setItem(slot, item(book, true)); container.setChanged(); return true; }
        return false;
    }
    @Override public boolean nameFilterAvailable(String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player != null && player.getTextFilter() != net.minecraft.server.network.TextFilter.DUMMY;
    }
    @Override public java.util.concurrent.CompletableFuture<String> filterName(String name, String target) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(target);
        if (player == null || !nameFilterAvailable(target)) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("Name filter unavailable"));
        return player.getTextFilter().processStreamMessage(name).thenApply(net.minecraft.server.network.FilteredText::filteredOrEmpty);
    }
    private ItemStack item(Book book, boolean live) {
        ItemStack result = new ItemStack(Items.WRITTEN_BOOK);
        result.set(DataComponents.WRITTEN_BOOK_CONTENT, content(book));
        if (live) { CompoundTag tag = new CompoundTag(); tag.putBoolean(marker(book), true); result.set(DataComponents.CUSTOM_DATA, CustomData.of(tag)); }
        return result;
    }
    @Override public void give(Audience audience, Book book, boolean live) {
        ServerPlayer player = player(audience);
        ItemStack item = item(book, live);
        if (!player.getInventory().add(item)) throw new IllegalArgumentException("Make space in your inventory, then run /subby book again.");
        audience.message((live ? "Live" : "Archive") + " book created: volume " + book.volume() + "." + (live ? " It updates while in a player's inventory or bound lectern." : ""));
    }
    @Override public LecternTarget targetedLectern(Audience audience) {
        ServerPlayer player = player(audience);
        HitResult hit = player.pick(6, 0, false);
        if (!(hit instanceof BlockHitResult block) || !(player.level().getBlockEntity(block.getBlockPos()) instanceof LecternBlockEntity))
            throw new IllegalArgumentException("Look at a lectern within six blocks.");
        BlockPos pos = block.getBlockPos();
        return new LecternTarget(player.level().dimension().toString(), pos.getX(), pos.getY(), pos.getZ());
    }
    @Override public void refresh(Book book, List<LecternTarget> lecterns) {
        refresh(book, lecterns, "");
    }
    @Override public void refresh(Book book, List<LecternTarget> lecterns, String boundPlayer) {
        WrittenBookContent content = content(book);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!boundPlayer.isEmpty() && !player.getScoreboardName().equalsIgnoreCase(boundPlayer)) continue;
            boolean changed = false;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (live(stack, book) && !content.equals(stack.get(DataComponents.WRITTEN_BOOK_CONTENT))) {
                    stack.set(DataComponents.WRITTEN_BOOK_CONTENT, content); changed = true;
                }
            }
            if (changed) { inventory.setChanged(); player.containerMenu.broadcastChanges(); }
        }
        for (ServerLevel level : server.getAllLevels()) for (LecternTarget target : lecterns) {
            if (!level.dimension().toString().equals(target.world())) continue;
            BlockPos pos = new BlockPos(target.x(), target.y(), target.z());
            if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof LecternBlockEntity lectern)) continue;
            if (lectern.getBook().isEmpty()) {
                LecternBlock.tryPlaceBook(null, level, pos, level.getBlockState(pos), item(book, true));
            } else if (live(lectern.getBook(), book) && !content.equals(lectern.getBook().get(DataComponents.WRITTEN_BOOK_CONTENT))) {
                lectern.setBook(item(book, true)); lectern.setChanged();
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
                level.updateNeighbourForOutputSignal(pos, level.getBlockState(pos).getBlock());
            }
        }
    }
    @Override public void announce(String name) { server.getPlayerList().broadcastSystemMessage(Component.literal("[Subby] " + name + " joined the subscriber book!"), false); }
    @Override public List<String> players() { return server.getPlayerList().getPlayers().stream().map(p -> p.getScoreboardName()).toList(); }
    @Override public PlayerState playerState(String name) {
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        if (player == null) return null;
        boolean boss = !player.level().getEntitiesOfClass(net.minecraft.world.entity.boss.wither.WitherBoss.class, player.getBoundingBox().inflate(128)).isEmpty()
                || !player.level().getEntitiesOfClass(net.minecraft.world.entity.boss.enderdragon.EnderDragon.class, player.getBoundingBox().inflate(128)).isEmpty();
        var raid = ((ServerLevel) player.level()).getRaidAt(player.blockPosition()); boss |= raid != null && raid.isActive() && !raid.isStopped();
        return new PlayerState(name, Compat.dimension(player), player.getHealth(), server.getWorldData().isHardcore(), boss);
    }
    @Override public void action(Rules.Action action, Subscriber event, String target) {
        if (action.type().equals("message")) { action(action, event); return; }
        for (String command : ActionCommands.create(action, event, target)) server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }
    @Override public void action(Rules.Action action, Subscriber event) {
        if (action.type().equals("message")) server.getPlayerList().broadcastSystemMessage(Component.literal(action.message(event)), false);
        else {
            String command = action.type().equals("function") ? "function " + action.value() :
                    "execute as @a at @s run playsound " + action.value() + " master @s ~ ~ ~ 0.6 1";
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
    }
}
