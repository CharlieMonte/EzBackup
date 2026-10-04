package com.ezbackup;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.ArrayList;

/**
 * THE version-specific layer of the server-side code. Every Minecraft / Fabric call the backup code (commands,
 * workflow) makes that has changed (or is likely to change) between game versions lives in this one file, so
 * porting that part to another Minecraft version means editing this file, {@code gradle.properties}, and (if the
 * event names changed) {@link EzBackup}. The GUI in the {@code client} package is NOT covered: it is written directly
 * against the 1.20.1 client classes (GuiGraphics, Button.builder, Fabric's screen events, ...) and has to be ported
 * separately. See README.md, "Porting to other Minecraft versions".
 *
 * Written for: Minecraft 1.20.1, Fabric Loader 0.15.x + Fabric API 0.92.x (official Mojang mappings). Apart from the
 * mod-loader calls at the top of the class this file is identical to the 1.20.1 Forge version.
 *
 * Differences from the Forge 1.20.1 version of this file:
 *   - there is no "server side only" flag: a Fabric server does not require its clients to have the same mods
 *   - the config folder and the mod version come from FabricLoader instead of FMLPaths / ModList
 */
final class McCompat {

    private McCompat() {
    }

    // ---------------------------------------------------------------------------------------------
    // Mod setup
    // ---------------------------------------------------------------------------------------------

    /** The game's config folder (where ezbackup.properties lives). */
    static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /** The version of an installed mod (for example this one), or "unknown" if it cannot be read. */
    static String modVersion(String modId) {
        try {
            return FabricLoader.getInstance().getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** The mod loader this build is made for ("Fabric"). Sent with suggestions so the developer knows which build the player uses. */
    static String modLoaderName() {
        return "Fabric";
    }

    /** The Minecraft version the game is running (for example "1.20.1"), or "unknown" if it cannot be read. */
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
        source.sendSuccess(() -> Component.literal(message), false);
    }

    /** A yellow chat line for something the player should notice but that is not an error (errors are red, see sendFailure). */
    static void sendWarning(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.YELLOW), false);
    }

    static void sendFailure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
    }

    /** Sends one chat line to one player (used for restore progress while that player waits in the holding dimension). */
    static void tellPlayer(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    /** Disconnects a player with a message on the "disconnected" screen. */
    static void disconnect(ServerPlayer player, String message) {
        player.connection.disconnect(Component.literal(message));
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
