package com.ezbackup;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.Mth;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Everything /backup restore needs from Minecraft's internals that is NOT a stable, documented API: closing and
 * rebuilding the server's dimensions while the server keeps running, the temporary holding dimension, and moving a
 * player's whole saved state in and out. This is the port-hot-spot of the feature (see FORKABILITY_NOTES.txt): a port
 * to another Minecraft version rewrites this class and nothing else in the restore code.
 *
 * <h3>How it avoids depending on names</h3>
 * {@code MinecraftServer.levels}, the world storage and the {@code ServerLevel} constructor are private. Instead of
 * names (which differ between Mojang names and the runtime's SRG names) they are found by TYPE: "the field of type
 * Map&lt;ResourceKey, ServerLevel&gt;", "the constructor whose first parameter is a MinecraftServer". The constructor's
 * arguments are filled by parameter type too (in 1.20.1: MinecraftServer, Executor, LevelStorageAccess, ServerLevelData,
 * ResourceKey, LevelStem, ChunkProgressListener, boolean isDebug, long biomeZoomSeed, List of CustomSpawner,
 * boolean tickTime, RandomSequences). {@link #unavailableReason()} runs all of these lookups once and the
 * restore command refuses to start (changing nothing) if one fails, so a mismatching Minecraft version gives a clear
 * message, not a half-restored world.
 *
 * <h3>Status</h3>
 * Ported from the Forge 1.20.1 version to Fabric. It is the part most likely to break on another Minecraft or
 * Fabric version, so test /backup restore first after any change.
 * Differences from the Forge 1.20.1 version: Forge's {@code LevelEvent.Load/Unload} and the player-changed-dimension
 * event are replaced by Fabric API's {@code ServerWorldEvents.LOAD/UNLOAD} and
 * {@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD}; Forge's cached dimension array does not exist on
 * Fabric (vanilla ticks the dimension map directly), so that workaround is gone; the command storage is found by its
 * constructor instead of its class name (class names are obfuscated at runtime on Fabric).
 * Differences from 1.18.2: the dimension constructor takes a {@code LevelStem} and a {@code RandomSequences}; the dragon
 * fight is a typed {@code EndDragonFight.Data}; {@code Registry.DIMENSION_REGISTRY} became {@code Registries.DIMENSION};
 * {@code WorldEvent} became {@code LevelEvent}; {@code Entity.getLevel()} became {@code level()}.
 *
 * Every method here must be called on the server thread.
 */
final class McWorldSwap {

    private static final ResourceLocation VOID_ID = new ResourceLocation("ezbackup:restore_void");

    /**
     * The holding dimension as a dimension JSON (what a datapack would contain): the overworld's dimension type with
     * a flat generator that makes nothing but air in the "the_void" biome and no structures. It is parsed with the
     * game's own codec at runtime. (A dimension JSON in the mod jar is NOT used: a datapack dimension is only
     * added to a world when the world is created, so it would be missing from every existing world.)
     */
    private static final String VOID_STEM_JSON = "{\"type\":\"minecraft:overworld\",\"generator\":{\"type\":\"minecraft:flat\","
            + "\"settings\":{\"biome\":\"minecraft:the_void\",\"features\":false,\"lakes\":false,"
            + "\"layers\":[{\"block\":\"minecraft:air\",\"height\":1}],\"structure_overrides\":[]}}}";

    /** What it takes to build a dimension again after it was closed. Opaque to the caller. */
    static final class LevelSpec {
        private final ResourceKey<Level> key;
        private final LevelStem stem;
        private final ServerLevelData data;
        private final boolean debug;
        /** The world seed (not the biome zoom seed; {@link #construct} converts it). */
        private final long seed;
        private final List<?> spawners;

        private LevelSpec(ResourceKey<Level> key, LevelStem stem, ServerLevelData data, boolean debug, long seed,
                          List<?> spawners) {
            this.key = key;
            this.stem = stem;
            this.data = data;
            this.debug = debug;
            this.seed = seed;
            this.spawners = spawners;
        }

        ResourceKey<Level> key() {
            return key;
        }
    }

    // Looked up once by selfCheck()
    private static boolean checked;
    private static String problem;
    private static Field levelsField;
    private static Field storageField;
    private static Constructor<?> levelConstructor;
    private static Field spawnersField;
    private static Method readScoreboard;      // optional
    private static Field commandStorageField;  // optional

    private McWorldSwap() {
    }

    static ResourceKey<Level> voidKey() {
        return ResourceKey.create(Registries.DIMENSION, VOID_ID);
    }

    // ---------------------------------------------------------------------------------------------
    // Self check
    // ---------------------------------------------------------------------------------------------

    /** Null if everything /backup restore needs was found; otherwise a player-readable reason it cannot be used. */
    static synchronized String unavailableReason() {
        if (!checked) {
            checked = true;
            try {
                problem = lookUp();
            } catch (Throwable t) {
                problem = "unexpected error while checking Minecraft's internals (" + t + ")";
            }
            if (problem != null) {
                EzBackup.LOGGER.error("/backup restore is unavailable: {}", problem);
            }
        }
        return problem == null ? null : "Restore is not available with this Minecraft version: " + problem;
    }

    private static String lookUp() throws Exception {
        levelsField = null;
        storageField = null;
        for (Field f : MinecraftServer.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType()) && f.getGenericType() instanceof ParameterizedType pt) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length == 2 && raw(args[0]) == ResourceKey.class && raw(args[1]) == ServerLevel.class) {
                    f.setAccessible(true);
                    levelsField = f;
                }
            } else if (f.getType() == LevelStorageSource.LevelStorageAccess.class) {
                f.setAccessible(true);
                storageField = f;
            } else if (hasConstructorTaking(f.getType(), DimensionDataStorage.class)) {
                // The command storage (class names are obfuscated at runtime on Fabric, so it is found by its constructor)
                f.setAccessible(true);
                commandStorageField = f;
            }
        }
        if (levelsField == null) {
            return "the server's dimension table was not found";
        }
        if (storageField == null) {
            return "the server's world storage was not found";
        }

        levelConstructor = null;
        for (Constructor<?> c : ServerLevel.class.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length >= 8 && p[0] == MinecraftServer.class) {
                levelConstructor = c;
            }
        }
        if (levelConstructor == null) {
            return "the dimension constructor was not found";
        }
        int booleans = 0;
        for (Class<?> p : levelConstructor.getParameterTypes()) {
            if (p == boolean.class) {
                booleans++;
            }
            if (!recognised(p)) {
                return "the dimension constructor has an unexpected parameter (" + p.getSimpleName() + ")";
            }
        }
        if (booleans != 2) {
            return "the dimension constructor has an unexpected number of yes/no parameters";
        }
        levelConstructor.setAccessible(true);

        spawnersField = null;
        for (Field f : ServerLevel.class.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType()) && f.getGenericType() instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length == 1 && raw(pt.getActualTypeArguments()[0]) == CustomSpawner.class) {
                f.setAccessible(true);
                spawnersField = f;
            }
        }
        if (spawnersField == null) {
            return "the dimensions' mob spawner list was not found";
        }

        readScoreboard = null;
        for (Method m : MinecraftServer.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == DimensionDataStorage.class && m.getReturnType() == void.class) {
                m.setAccessible(true);
                readScoreboard = m;
            }
        }
        return null;
    }

    private static boolean hasConstructorTaking(Class<?> type, Class<?> parameter) {
        if (type.isPrimitive() || type.isArray() || type.isInterface() || type.getName().startsWith("java.")) {
            return false;
        }
        try {
            type.getConstructor(parameter);
            return true;
        } catch (NoSuchMethodException | SecurityException e) {
            return false;
        }
    }

    private static boolean recognised(Class<?> p) {
        return p == MinecraftServer.class || p == Executor.class || p == LevelStorageSource.LevelStorageAccess.class
                || p == ServerLevelData.class || p == ResourceKey.class || p == LevelStem.class
                || p == ChunkProgressListener.class || p == boolean.class || p == long.class || p == List.class
                || p == RandomSequences.class;
    }

    private static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) {
            return c;
        }
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c;
        }
        return Object.class;
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceKey<Level>, ServerLevel> levels(MinecraftServer server) {
        try {
            return (Map<ResourceKey<Level>, ServerLevel>) levelsField.get(server);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Call after EVERY change to the dimension map. Vanilla ticks {@code getAllLevels()}, which is the map itself, so
     * nothing has to be refreshed on Fabric (the Forge version had to rebuild a cached array here); this only logs
     * which dimensions are registered now.
     */
    private static void levelsChanged(MinecraftServer server) {
        StringBuilder sb = new StringBuilder();
        for (ServerLevel level : server.getAllLevels()) {
            sb.append(level.dimension().location()).append(' ');
        }
        EzBackup.LOGGER.info("[restore] dimensions now registered: {}", sb.toString().trim());
    }

    // ---------------------------------------------------------------------------------------------
    // Building a dimension
    // ---------------------------------------------------------------------------------------------

    /**
     * Builds one dimension the way {@code MinecraftServer.createLevels} does. {@code sequences} is the overworld's
     * {@code RandomSequences} for every dimension but the overworld, and null for the overworld itself (it then loads
     * them from its own data folder).
     */
    private static ServerLevel construct(MinecraftServer server, LevelSpec spec, boolean tickTime,
                                         RandomSequences sequences) throws Exception {
        Object storage = storageField.get(server);
        ChunkProgressListener quiet = (ChunkProgressListener) Proxy.newProxyInstance(
                McWorldSwap.class.getClassLoader(), new Class<?>[]{ChunkProgressListener.class}, (proxy, method, args) -> null);
        Class<?>[] types = levelConstructor.getParameterTypes();
        Object[] args = new Object[types.length];
        int booleansSeen = 0;
        for (int i = 0; i < types.length; i++) {
            Class<?> p = types[i];
            if (p == MinecraftServer.class) {
                args[i] = server;
            } else if (p == Executor.class) {
                args[i] = Util.backgroundExecutor();
            } else if (p == LevelStorageSource.LevelStorageAccess.class) {
                args[i] = storage;
            } else if (p == ServerLevelData.class) {
                args[i] = spec.data;
            } else if (p == ResourceKey.class) {
                args[i] = spec.key;
            } else if (p == LevelStem.class) {
                args[i] = spec.stem;
            } else if (p == ChunkProgressListener.class) {
                args[i] = quiet;
            } else if (p == boolean.class) {
                args[i] = booleansSeen++ == 0 ? spec.debug : tickTime; // vanilla order: isDebug, then tickTime
            } else if (p == long.class) {
                args[i] = BiomeManager.obfuscateSeed(spec.seed); // this parameter is the biome zoom seed
            } else if (p == List.class) {
                args[i] = spec.spawners;
            } else if (p == RandomSequences.class) {
                args[i] = sequences;
            }
        }
        try {
            return (ServerLevel) levelConstructor.newInstance(args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ex ? ex : e;
        }
    }

    private static LevelSpec capture(MinecraftServer server, ServerLevel level) throws Exception {
        Object data = level.getLevelData();
        if (!(data instanceof ServerLevelData serverData)) {
            throw new IllegalStateException("Dimension " + level.dimension().location() + " has unexpected level data");
        }
        List<?> spawners = (List<?>) spawnersField.get(level);
        LevelStem stem = new LevelStem(level.dimensionTypeRegistration(), level.getChunkSource().getGenerator());
        return new LevelSpec(level.dimension(), stem, serverData, level.isDebug(), level.getSeed(), spawners);
    }

    // ---------------------------------------------------------------------------------------------
    // The holding (void) dimension
    // ---------------------------------------------------------------------------------------------

    /** Creates the holding dimension and registers it with the server. Does nothing if it already exists. */
    static ServerLevel openVoidLevel(MinecraftServer server) throws Exception {
        ServerLevel existing = levels(server).get(voidKey());
        if (existing != null) {
            return existing;
        }
        JsonElement json = JsonParser.parseString(VOID_STEM_JSON);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        LevelStem stem = LevelStem.CODEC.parse(ops, json).getOrThrow(false, message -> {
            throw new IllegalStateException("Could not build the holding dimension: " + message);
        });
        ServerLevelData data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
        ServerLevel overworld = server.overworld();
        LevelSpec spec = new LevelSpec(voidKey(), stem, data, false, overworld.getSeed(), List.of());
        ServerLevel level = construct(server, spec, false, overworld.getRandomSequences());
        levels(server).put(voidKey(), level);
        levelsChanged(server);
        // Players keep their game mode while they wait, so give the empty dimension a floor (invisible, never saved: the dimension is deleted afterwards).
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(dx, 63, dz), Blocks.BARRIER.defaultBlockState(), 2);
            }
        }
        return level;
    }

    /** Closes and unregisters the holding dimension (no events are posted for it, so other mods never see it). */
    static void removeVoidLevel(MinecraftServer server) {
        ServerLevel level = levels(server).remove(voidKey());
        if (level != null) {
            levelsChanged(server);
            try {
                level.close();
            } catch (IOException e) {
                EzBackup.LOGGER.warn("Could not close the holding dimension cleanly", e);
            }
        }
    }

    static boolean isInVoid(ServerPlayer player) {
        return player.level().dimension().equals(voidKey());
    }

    // ---------------------------------------------------------------------------------------------
    // Closing and rebuilding the real dimensions
    // ---------------------------------------------------------------------------------------------

    /**
     * Saves nothing (the caller saved already), tells mods the dimensions unload, then closes every dimension except
     * the holding one and unregisters it, which releases all region/entity/POI files. {@code specs} is filled BEFORE
     * anything is closed so the caller can rebuild even if closing fails half way. Every dimension must be empty of players.
     */
    static void closeRealLevels(MinecraftServer server, List<LevelSpec> specs) throws Exception {
        Map<ResourceKey<Level>, ServerLevel> map = levels(server);
        List<ServerLevel> open = new ArrayList<>();
        for (ServerLevel level : map.values()) {
            if (!level.dimension().equals(voidKey())) {
                if (!level.players().isEmpty()) {
                    throw new IllegalStateException("Dimension " + level.dimension().location() + " still has players in it");
                }
                open.add(level);
            }
        }
        List<LevelSpec> captured = new ArrayList<>();
        for (ServerLevel level : open) {
            captured.add(capture(server, level));
        }
        captured.sort((a, b) -> Boolean.compare(b.key.equals(Level.OVERWORLD), a.key.equals(Level.OVERWORLD)));
        specs.clear();
        specs.addAll(captured);

        for (ServerLevel level : open) {
            ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level);
        }
        IOException firstError = null;
        for (ServerLevel level : open) {
            map.remove(level.dimension());
        }
        levelsChanged(server);
        for (ServerLevel level : open) {
            try {
                level.close();
            } catch (IOException e) {
                if (firstError == null) {
                    firstError = e;
                } else {
                    firstError.addSuppressed(e);
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    /** Builds every dimension in {@code specs} again (overworld first), the way {@code MinecraftServer.createLevels} does. */
    static void rebuildLevels(MinecraftServer server, List<LevelSpec> specs) throws Exception {
        Map<ResourceKey<Level>, ServerLevel> map = levels(server);
        List<ServerLevel> built = new ArrayList<>();
        RandomSequences sequences = null; // the overworld (built first) loads its own; the others share the overworld's
        for (LevelSpec spec : specs) {
            boolean isOverworld = spec.key.equals(Level.OVERWORLD);
            ServerLevel level = construct(server, spec, isOverworld, isOverworld ? null : sequences);
            if (isOverworld) {
                sequences = level.getRandomSequences();
            }
            map.put(spec.key, level);
            built.add(level);
        }
        levelsChanged(server);
        ServerLevel overworld = map.get(Level.OVERWORLD);
        if (overworld == null) {
            throw new IllegalStateException("The overworld was not rebuilt");
        }
        overworld.getWorldBorder().applySettings(server.getWorldData().overworldData().getWorldBorder());
        server.getPlayerList().addWorldborderListener(overworld);
        for (ServerLevel level : built) {
            if (level != overworld) {
                overworld.getWorldBorder().addListener(new BorderChangeListener.DelegateBorderChangeListener(level.getWorldBorder()));
            }
        }
        for (ServerLevel level : built) {
            ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level);
        }
        refreshServerWideState(server);
    }

    /**
     * Reloads what the server reads from the overworld's data folder only once at start: the scoreboard and the command
     * storage. Best effort: a failure is logged, not fatal (the world files themselves are already correct).
     */
    private static void refreshServerWideState(MinecraftServer server) {
        try {
            DimensionDataStorage storage = server.overworld().getDataStorage();
            if (readScoreboard != null) {
                Scoreboard scoreboard = server.getScoreboard();
                for (Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
                    scoreboard.removeObjective(objective);
                }
                for (PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
                    scoreboard.removePlayerTeam(team);
                }
                readScoreboard.invoke(server, storage);
            }
            if (commandStorageField != null) {
                Constructor<?> c = commandStorageField.getType().getConstructor(DimensionDataStorage.class);
                commandStorageField.set(server, c.newInstance(storage));
            }
        } catch (Throwable t) {
            EzBackup.LOGGER.warn("Could not reload the scoreboard / command storage after the restore", t);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // World-wide state kept in level.dat
    // ---------------------------------------------------------------------------------------------

    /**
     * The parts of level.dat that describe the WORLD's state, as one tag with level.dat's own key names (so the same
     * method {@link #applyLevelData} also applies the Data tag of a backup's level.dat). Deliberately NOT included:
     * game rules, difficulty, data packs, world generation settings and seed, version and mod information. Those are
     * settings of this installation; applying a backup's copies could break the running server.
     */
    static CompoundTag snapshotLevelData(MinecraftServer server) {
        ServerLevelData d = server.getWorldData().overworldData();
        CompoundTag t = new CompoundTag();
        t.putLong("Time", d.getGameTime());
        t.putLong("DayTime", d.getDayTime());
        t.putInt("SpawnX", d.getXSpawn());
        t.putInt("SpawnY", d.getYSpawn());
        t.putInt("SpawnZ", d.getZSpawn());
        t.putFloat("SpawnAngle", d.getSpawnAngle());
        t.putBoolean("raining", d.isRaining());
        t.putInt("rainTime", d.getRainTime());
        t.putBoolean("thundering", d.isThundering());
        t.putInt("thunderTime", d.getThunderTime());
        t.putInt("clearWeatherTime", d.getClearWeatherTime());
        t.putInt("WanderingTraderSpawnDelay", d.getWanderingTraderSpawnDelay());
        t.putInt("WanderingTraderSpawnChance", d.getWanderingTraderSpawnChance());
        if (d.getWanderingTraderId() != null) {
            t.putUUID("WanderingTraderId", d.getWanderingTraderId());
        }
        d.getWorldBorder().write(t);
        // The dragon fight is a typed record; level.dat stores it as its codec's encoding.
        EndDragonFight.Data fight = server.getWorldData().endDragonFightData();
        if (fight != null) {
            EndDragonFight.Data.CODEC.encodeStart(NbtOps.INSTANCE, fight).result()
                    .ifPresent(encoded -> t.put("DragonFight", encoded));
        }
        return t;
    }

    /** Applies a tag made by {@link #snapshotLevelData} or the Data tag of a level.dat. Call BEFORE rebuilding the dimensions (the End reads the dragon fight when it is built). */
    static void applyLevelData(MinecraftServer server, CompoundTag t) {
        ServerLevelData d = server.getWorldData().overworldData();
        d.setGameTime(t.getLong("Time"));
        d.setDayTime(t.getLong("DayTime"));
        if (t.contains("SpawnX", 99)) {
            d.setSpawn(new BlockPos(t.getInt("SpawnX"), t.getInt("SpawnY"), t.getInt("SpawnZ")), t.getFloat("SpawnAngle"));
        }
        d.setRaining(t.getBoolean("raining"));
        d.setRainTime(t.getInt("rainTime"));
        d.setThundering(t.getBoolean("thundering"));
        d.setThunderTime(t.getInt("thunderTime"));
        d.setClearWeatherTime(t.getInt("clearWeatherTime"));
        d.setWanderingTraderSpawnDelay(t.getInt("WanderingTraderSpawnDelay"));
        d.setWanderingTraderSpawnChance(t.getInt("WanderingTraderSpawnChance"));
        d.setWanderingTraderId(t.hasUUID("WanderingTraderId") ? t.getUUID("WanderingTraderId") : null);
        d.setWorldBorder(WorldBorder.Settings.read(new Dynamic<>(NbtOps.INSTANCE, t), WorldBorder.DEFAULT_SETTINGS));
        EndDragonFight.Data fight = EndDragonFight.Data.DEFAULT;
        if (t.contains("DragonFight", 10)) {
            fight = EndDragonFight.Data.CODEC.parse(NbtOps.INSTANCE, t.getCompound("DragonFight")).result()
                    .orElse(EndDragonFight.Data.DEFAULT);
        }
        server.getWorldData().setEndDragonFightData(fight);
    }

    /** The Data tag of a level.dat file, or null if it cannot be read. */
    static CompoundTag readLevelDat(Path levelDat) {
        try {
            return NbtIo.readCompressed(levelDat.toFile()).getCompound("Data");
        } catch (IOException e) {
            EzBackup.LOGGER.warn("Could not read {}", levelDat, e);
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Players
    // ---------------------------------------------------------------------------------------------

    /** The player's complete saved state (position, inventory, ender chest, XP, effects, spawn point and every mod's capability data such as Curios / Artifacts slots). */
    static CompoundTag savePlayer(ServerPlayer player) {
        return player.saveWithoutId(new CompoundTag());
    }

    static CompoundTag readPlayerFile(Path file) {
        try {
            return NbtIo.readCompressed(file.toFile());
        } catch (IOException e) {
            EzBackup.LOGGER.warn("Could not read player file {}", file, e);
            return null;
        }
    }

    static void writePlayerFile(Path file, CompoundTag tag) throws IOException {
        File f = file.toFile();
        f.getParentFile().mkdirs();
        NbtIo.writeCompressed(tag, f);
    }

    /**
     * Moves a player into the holding dimension: drops any ride, then teleports them to (0.5, 64, 0.5), which is standing
     * on the barrier floor {@link #openVoidLevel} placed. Their game mode is not changed.
     */
    static void evacuate(MinecraftServer server, ServerPlayer player) {
        ServerLevel holding = levels(server).get(voidKey());
        if (holding == null) {
            throw new IllegalStateException("The holding dimension is missing");
        }
        ServerLevel from = player.serverLevel();
        player.ejectPassengers();
        player.stopRiding();
        player.teleportTo(holding, 0.5, 64.0, 0.5, 0.0F, 0.0F);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.invoker().afterChangeWorld(player, from, holding);
    }

    /**
     * Brings a player out of the holding dimension.
     *
     * <p>The player arrives at the position (and in the dimension) saved in {@code tag}, 0.01 blocks higher so they do
     * not sink into the block they stood on; with no usable position they arrive on top of the overworld's spawn. The
     * destination chunk is loaded first, then the client is resynced (inventory, recipes, advancements, statistics).
     *
     * @param tag the saved state to load into the player (a backup's player file, or the pre-restore copy), or
     *            null to keep the player exactly as they are and only move them to the world spawn. The game mode is
     *            never changed: the player keeps the mode they had before the restore started (the abilities loaded
     *            from {@code tag} are re-set to match it).
     */
    static void returnPlayer(MinecraftServer server, ServerPlayer player, CompoundTag tag) {
        McCompat.returnHeldItems(player); // an item still on the cursor would otherwise survive the inventory reload below
        ServerLevel from = player.serverLevel();
        ServerLevel target = null;
        double x = 0;
        double y = 0;
        double z = 0;
        float yRot = 0;
        float xRot = 0;
        if (tag != null) {
            tag.remove("RootVehicle"); // the vehicle was not part of the world that was restored
            GameType kept = player.gameMode.getGameModeForPlayer();
            player.load(tag);
            // load() also applied the backup's saved game mode (playerGameType); put the player's real mode back
            if (player.gameMode.getGameModeForPlayer() != kept) {
                player.setGameMode(kept);
            }
            // load() also read the backup's abilities (fly / invulnerable / instabuild); make them match the mode the player is really in
            kept.updatePlayerAbilities(player.getAbilities());
            player.onUpdateAbilities();
            ResourceKey<Level> dim = Level.OVERWORLD;
            if (tag.get("Dimension") != null) {
                dim = DimensionType.parseLegacy(new Dynamic<>(NbtOps.INSTANCE, tag.get("Dimension"))).result().orElse(Level.OVERWORLD);
            }
            target = server.getLevel(dim);
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            yRot = player.getYRot();
            xRot = player.getXRot();
            if (target != null && !(Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z))) {
                target = null;
            }
        }
        if (target == null) {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            overworld.getChunk(spawn); // loads it
            BlockPos top = overworld.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
            target = overworld;
            x = top.getX() + 0.5;
            y = top.getY();
            z = top.getZ() + 0.5;
            yRot = 0;
            xRot = 0;
        } else {
            target.getChunk(new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z))); // load the destination before arriving
        }
        player.teleportTo(target, x, y + 0.01, z, yRot, xRot); // 0.01 higher so the player does not sink into the block they stood on
        resync(server, player);
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.invoker().afterChangeWorld(player, from, target);
    }

    /** Gives a player a completely fresh start (used for the host when the backup has no data for them). Mod-specific slots cannot be cleared generically; players who can be disconnected get a truly fresh start by rejoining instead. */
    static void resetPlayer(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.setExperienceLevels(0);
        player.setExperiencePoints(0);
        player.totalExperience = 0;
        player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
    }

    /** Re-sends everything a client shows about the player and reloads the per-player files the server caches (advancements, statistics). */
    private static void resync(MinecraftServer server, ServerPlayer player) {
        try {
            server.getPlayerList().sendAllPlayerInfo(player);
            player.getRecipeBook().sendInitialRecipeBook(player);
            player.getAdvancements().reload(server.getAdvancements());
            player.getAdvancements().flushDirty(player);
            reloadStats(server, player);
        } catch (Throwable t) {
            EzBackup.LOGGER.warn("Could not fully resync {}", player.getGameProfile().getName(), t);
        }
    }

    private static void reloadStats(MinecraftServer server, ServerPlayer player) throws IOException {
        Path file = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_STATS_DIR)
                .resolve(player.getUUID() + ".json");
        String json = java.nio.file.Files.isRegularFile(file) ? java.nio.file.Files.readString(file) : "{}";
        player.getStats().parseLocal(server.getFixerUpper(), json);
        player.getStats().sendStats(player);
    }

}
