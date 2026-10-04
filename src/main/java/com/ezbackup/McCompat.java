package com.ezbackup;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.Util;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkConstants;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * THE version-specific layer of the server-side code. Every Minecraft / Forge call the backup code (commands,
 * workflow) makes that has changed (or is likely to change) between game versions lives in this one file, so
 * porting that part to another Minecraft version means editing this file, {@code gradle.properties}, and (if the
 * event names changed) {@link EzBackup}. The GUI in the {@code client} package is NOT covered: it is written directly
 * against the 1.18.2 client classes (TextComponent, PoseStack, ScreenEvent, ...) and has to be ported separately. See README.md, "Porting to another Minecraft version".
 *
 * Written for: Minecraft 1.18.2, Forge 40.x (official Mojang mappings).
 *
 * Things that are known to differ in later versions (check them first when porting):
 *   - chat text:      TextComponent (1.18)  ->  Component.literal(...) (1.19+); player.sendMessage(Component, UUID) (1.18-1.18.2)
 *                     -> player.sendSystemMessage(Component) (1.19+)  [tellPlayer]
 *   - sendSuccess():  takes a Component (1.18-1.19)  ->  takes a Supplier<Component> (1.20+)
 *   - server-only flag (markServerSideOnly): later Forge versions may expose this differently; check the Forge docs
 *     for the DisplayTest / IGNORESERVERONLY equivalent of your target version
 */
final class McCompat {

    private McCompat() {
    }

    // ---------------------------------------------------------------------------------------------
    // Mod setup
    // ---------------------------------------------------------------------------------------------

    /** Tells Forge this mod is server-side only, so players joining a LAN / e4mc world do not need it. */
    static void markServerSideOnly() {
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
    }

    /** The game's config folder (where ezbackup.properties lives). */
    static Path configDirectory() {
        return FMLPaths.CONFIGDIR.get();
    }

    /** The version of an installed mod (for example this one), or "unknown" if it cannot be read. */
    static String modVersion(String modId) {
        try {
            return ModList.get().getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** The Minecraft version the game is running (for example "1.18.2"), or "unknown" if it cannot be read. */
    static String minecraftVersion() {
        try {
            return SharedConstants.getCurrentVersion().getName();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Chat output
    // ---------------------------------------------------------------------------------------------

    static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(new TextComponent(message), false);
    }

    /** A yellow chat line for something the player should notice but that is not an error (errors are red, see sendFailure). */
    static void sendWarning(CommandSourceStack source, String message) {
        source.sendSuccess(new TextComponent(message).withStyle(ChatFormatting.YELLOW), false);
    }

    static void sendFailure(CommandSourceStack source, String message) {
        source.sendFailure(new TextComponent(message));
    }

    /** Sends one chat line to one player (used for restore progress while that player waits in the holding dimension). */
    static void tellPlayer(ServerPlayer player, String message) {
        player.sendMessage(new TextComponent(message), Util.NIL_UUID);
    }

    /** Disconnects a player with a message on the "disconnected" screen. */
    static void disconnect(ServerPlayer player, String message) {
        player.connection.disconnect(new TextComponent(message));
    }

    // ---------------------------------------------------------------------------------------------
    // World access
    // ---------------------------------------------------------------------------------------------

    /** The folder of the currently loaded world (the one that contains level.dat). */
    static Path worldFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT);
    }

    /**
     * Flushes everything to disk (chunks, entities, POI data, players). Returns false if the server
     * reports that saving failed.
     */
    static boolean saveWorld(MinecraftServer server) {
        returnHeldItems(server);
        return server.saveEverything(true, true, true);
    }

    /** {@link #returnHeldItems(ServerPlayer)} for every online player. Call on the server thread, right before saving. */
    static void returnHeldItems(MinecraftServer server) {
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            returnHeldItems(player);
        }
    }

    /**
     * The item on a player's mouse cursor (and the items in the 2x2 crafting grid of the inventory screen) live in
     * the open menu, not in the player's saved data, so a save would miss them. Closing the menu hands them back to
     * the inventory (or drops them if it is full) so they are included. Does nothing for players who hold nothing.
     */
    static void returnHeldItems(ServerPlayer player) {
        boolean holding = !player.containerMenu.getCarried().isEmpty();
        boolean craftingItems = false;
        if (player.containerMenu == player.inventoryMenu) {
            for (int i = 1; i <= 4; i++) { // slots 1-4 are the 2x2 crafting grid
                if (player.inventoryMenu.getSlot(i).hasItem()) {
                    craftingItems = true;
                }
            }
        }
        if (holding || craftingItems) {
            player.closeContainer();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------------------------------

    /**
     * Who may use /backup:
     *  - the player who owns this single-player / LAN / e4mc world, whether or not cheats are on
     *  - the console, and anyone with {@code permissionLevel} or higher
     * Other players who join your world can NOT use it, even if you gave them cheats/op level 2.
     */
    static boolean mayBackup(CommandSourceStack source, int permissionLevel) {
        if (source.getEntity() instanceof ServerPlayer player
                && source.getServer().isSingleplayerOwner(player.getGameProfile())) {
            return true;
        }
        return source.hasPermission(permissionLevel);
    }
}
