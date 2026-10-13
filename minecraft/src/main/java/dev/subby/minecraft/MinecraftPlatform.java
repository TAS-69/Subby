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
    }
    public MinecraftPlatform(MinecraftServer server) { this.server = server; }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<SubbyService> service) {
        dispatcher.register(Commands.literal("subby").requires(Compat::allowed)
                .executes(ctx -> dispatch(ctx.getSource(), service, new String[0]))
                .then(Commands.argument("arguments", StringArgumentType.greedyString()).executes(ctx ->
                        dispatch(ctx.getSource(), service, StringArgumentType.getString(ctx, "arguments").trim().split("\\s+")))));
    }
    private static int dispatch(CommandSourceStack source, Supplier<SubbyService> service, String[] args) {
        Source audience = new Source(source);
        if (service.get() == null) audience.message("Subby is unavailable. Check the server log for the startup error.");
        else service.get().command(audience, args);
        return 1;
    }
    @Override public void execute(Runnable action) { server.execute(action); }
    @Override public void log(String message) { LoggerFactory.getLogger("Subby").info(message); }
    private ServerPlayer player(Audience audience) {
        if (!(audience instanceof Source source) || !(source.source.getEntity() instanceof ServerPlayer player))
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
                book.pages().stream().map(page -> Filterable.passThrough((Component) Component.literal(page))).toList(), true);
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
        WrittenBookContent content = content(book);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
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
    @Override public void action(Rules.Action action, Subscriber event) {
        if (action.type().equals("message")) server.getPlayerList().broadcastSystemMessage(Component.literal(action.message(event)), false);
        else {
            String command = action.type().equals("function") ? "function " + action.value() :
                    "execute as @a at @s run playsound " + action.value() + " master @s ~ ~ ~ 0.6 1";
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
        }
    }
}
