package cn.alini.trueuuid.server;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Optional, narrow adapters for the pack's FTB 2001 APIs. No runtime dependency
 * is added. Missing/incompatible APIs fail before any identity/file changes.
 * All calls run on the Minecraft server thread.
 */
final class FtbMigrationCaches implements MigrationTransaction.CacheHooks {
    private final Path root;
    private final UUID from;
    private final UUID to;
    private Object teams;
    private Object chunks;
    private Object quests;
    private Class<?> essentials;
    private Map<UUID, Object> knownPlayers;
    private Map<UUID, Object> teamMap;
    private Map<UUID, Object> chunkMap;
    private Map<Object, Object> claims;
    private Map<UUID, Object> questMap;
    private Map<UUID, Object> essentialsMap;
    private final List<MapSnapshot> maps = new ArrayList<>();
    private final Map<Object, CompoundTag> snapshots = new IdentityHashMap<>();
    private final Set<Path> changed = new HashSet<>();
    private boolean applied;

    private record MapSnapshot(Map<Object, Object> map, Map<Object, Object> before) {}

    FtbMigrationCaches(Path root, UUID from, UUID to) {
        this.root = root;
        this.from = from;
        this.to = to;
    }

    @Override
    public void prepare() throws Exception {
        Class<?> teamsClass = optional("dev.ftb.mods.ftbteams.data.TeamManagerImpl");
        if (teamsClass != null) {
            teams = teamsClass.getField("INSTANCE").get(null);
            if (teams == null) throw new IOException("FTB Teams 尚未完成加载，已停止继承。");
            knownPlayers = map(invoke(teams, "getKnownPlayerTeams"));
            teamMap = map(invoke(teams, "getTeamMap"));
            remember(knownPlayers);
            remember(teamMap);
            snapshots(teamMap.values());
            Class<?> playerTeam = Class.forName("dev.ftb.mods.ftbteams.data.PlayerTeam");
            playerTeam.getConstructor(teamsClass, UUID.class);
            // Flush current progress before filesystem snapshots, without reloading other teams.
            invoke(teams, "saveNow");
        }
        Class<?> chunksClass = optional("dev.ftb.mods.ftbchunks.data.ClaimedChunkManagerImpl");
        if (chunksClass != null) {
            chunks = chunksClass.getMethod("getInstance").invoke(null);
            if (chunks == null || teams == null) throw new IOException("FTB Chunks 缓存不可用，已停止继承。");
            chunkMap = map(field(chunks, "teamData"));
            claims = map(field(chunks, "claimedChunks"));
            remember(chunkMap);
            remember(claims);
            snapshots(chunkMap.values());
            Class.forName("dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl").getConstructor(
                    chunksClass, Path.class, Class.forName("dev.ftb.mods.ftbteams.api.Team"));
            for (Object data : chunkMap.values()) invoke(data, "saveNow");
        }
        Class<?> questsClass = optional("dev.ftb.mods.ftbquests.quest.ServerQuestFile");
        if (questsClass != null) {
            quests = questsClass.getField("INSTANCE").get(null);
            if (quests == null) throw new IOException("FTB Quests 尚未完成加载，已停止继承。");
            questMap = map(field(quests, "teamDataMap"));
            remember(questMap);
            snapshots(questMap.values());
            Class.forName("dev.ftb.mods.ftbquests.quest.TeamData").getConstructor(
                    UUID.class, Class.forName("dev.ftb.mods.ftbquests.quest.BaseQuestFile"));
            for (Object data : questMap.values()) invoke(data, "saveIfChanged");
        }
        essentials = optional("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
        if (essentials != null) {
            essentialsMap = map(staticField(essentials, "MAP"));
            remember(essentialsMap);
            essentials.getMethod("saveAll").invoke(null);
        }
        for (MigrationFiles.Pair pair : MigrationFiles.pairs(root, from, to)) {
            if (Files.exists(pair.source())) changed.add(pair.target());
        }
        if (chunks != null && changed.contains(root.resolve("ftbchunks/" + to + ".snbt"))
                && !changed.contains(root.resolve("ftbteams/player/" + to + ".snbt"))) {
            throw new IOException("FTB Chunks 存在旧数据但缺少对应玩家队伍，已保留原档。");
        }
        if (teams != null || chunks != null || quests != null || essentials != null) {
            Class.forName("dev.ftb.mods.ftblibrary.snbt.SNBT").getMethod("read", Path.class);
        }
    }

    @Override
    public void filesApplied() throws Exception {
        applied = true;
        if (teams != null) {
            Object previousPersonal = knownPlayers.get(from);
            Path playerFile = root.resolve("ftbteams/player/" + to + ".snbt");
            if (changed.contains(playerFile)) {
                Object player = construct(Class.forName("dev.ftb.mods.ftbteams.data.PlayerTeam"), teams, to);
                deserialize(player, read(playerFile));
                knownPlayers.remove(from);
                teamMap.remove(from);
                knownPlayers.put(to, player);
                teamMap.put(to, player);
                if (previousPersonal != null) {
                    Object effective = invoke(previousPersonal, "getEffectiveTeam");
                    if (effective != previousPersonal) invoke(player, "setEffectiveTeam", effective);
                }
            }
            for (var entry : new ArrayList<>(teamMap.entrySet())) {
                if (entry.getKey().equals(to)) continue;
                Object type = invoke(entry.getValue(), "getType");
                String directory = ((Enum<?>) type).name().toLowerCase(java.util.Locale.ROOT);
                Path path = root.resolve("ftbteams/" + directory + "/" + entry.getKey() + ".snbt");
                if (changed.contains(path)) deserialize(entry.getValue(), read(path));
            }
            setField(teams, "nameMap", null);
            invoke(teams, "markDirty");
        }
        if (chunks != null) {
            Path playerFile = root.resolve("ftbchunks/" + to + ".snbt");
            if (changed.contains(playerFile)) {
                Object previous = chunkMap.remove(from);
                if (previous != null) {
                    Set<?> previousClaims = new HashSet<>((java.util.Collection<?>) invoke(previous, "getClaimedChunks"));
                    claims.values().removeIf(previousClaims::contains);
                }
                Object team = teamMap.get(to);
                if (team == null) throw new IOException("迁移后的 FTB 玩家队伍不存在。");
                Object data = construct(Class.forName("dev.ftb.mods.ftbchunks.data.ChunkTeamDataImpl"), chunks, playerFile, team);
                deserialize(data, read(playerFile));
                chunkMap.put(to, data);
            }
            for (var entry : new ArrayList<>(chunkMap.entrySet())) {
                if (entry.getKey().equals(to)) continue;
                Path path = (Path) invoke(entry.getValue(), "getFile");
                if (changed.contains(path)) deserialize(entry.getValue(), read(path));
            }
            invoke(chunks, "clearForceLoadedCache");
        }
        if (quests != null) {
            Path playerFile = root.resolve("ftbquests/" + to + ".snbt");
            if (changed.contains(playerFile)) {
                Object data = construct(Class.forName("dev.ftb.mods.ftbquests.quest.TeamData"), to, quests);
                deserialize(data, read(playerFile));
                questMap.remove(from);
                questMap.put(to, data);
            }
            for (var entry : new ArrayList<>(questMap.entrySet())) {
                if (entry.getKey().equals(to)) continue;
                Path path = root.resolve("ftbquests/" + entry.getKey() + ".snbt");
                if (changed.contains(path)) deserialize(entry.getValue(), read(path));
            }
        }
        if (essentialsMap != null) {
            // Both identities are offline; the next real login loads the migrated file.
            essentialsMap.remove(from);
            essentialsMap.remove(to);
        }
    }

    @Override
    public void rollback() throws Exception {
        if (!applied) return;
        Exception failure = null;
        for (var snapshot : snapshots.entrySet()) {
            try { deserialize(snapshot.getKey(), snapshot.getValue()); }
            catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        for (MapSnapshot snapshot : maps) {
            snapshot.map().clear();
            snapshot.map().putAll(snapshot.before());
        }
        if (teams != null) setField(teams, "nameMap", null);
        if (chunks != null) invoke(chunks, "clearForceLoadedCache");
        if (failure != null) throw failure;
    }

    private void snapshots(java.util.Collection<?> objects) throws Exception {
        for (Object value : objects) snapshots.put(value, (CompoundTag) invoke(value, "serializeNBT"));
    }

    @SuppressWarnings("unchecked")
    private void remember(Map<?, ?> value) {
        Map<Object, Object> map = (Map<Object, Object>) value;
        maps.add(new MapSnapshot(map, new HashMap<>(map)));
    }

    @SuppressWarnings("unchecked")
    private static <K> Map<K, Object> map(Object value) { return (Map<K, Object>) value; }

    private CompoundTag read(Path path) throws Exception {
        MigrationTransaction.checkPath(root, path);
        Object result = Class.forName("dev.ftb.mods.ftblibrary.snbt.SNBT").getMethod("read", Path.class).invoke(null, path);
        if (!(result instanceof CompoundTag tag)) throw new IOException("FTB 数据解析失败: " + path);
        return tag;
    }

    private static void deserialize(Object object, CompoundTag tag) throws Exception {
        invoke(object, "deserializeNBT", tag);
    }

    private static Class<?> optional(String name) throws ClassNotFoundException {
        try { return Class.forName(name, false, FtbMigrationCaches.class.getClassLoader()); }
        catch (ClassNotFoundException absent) { return null; }
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) return method.invoke(target, args);
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name);
    }

    private static Object construct(Class<?> type, Object... args) throws Exception {
        for (Constructor<?> ctor : type.getConstructors()) {
            if (matches(ctor.getParameterTypes(), args)) return ctor.newInstance(args);
        }
        throw new NoSuchMethodException(type.getName() + " constructor");
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) if (args[i] != null && !types[i].isInstance(args[i])) return false;
        return true;
    }

    private static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object field(Object object, String name) throws Exception {
        return declaredField(object.getClass(), name).get(object);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        return declaredField(type, name).get(null);
    }

    private static void setField(Object object, String name, Object value) throws Exception {
        declaredField(object.getClass(), name).set(object, value);
    }
}
