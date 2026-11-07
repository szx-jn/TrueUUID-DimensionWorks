package cn.alini.trueuuid.server;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

public final class PlayerDataMigration {
    public record OfflineData(UUID offlineUuid, String summary) {}
    public record CleanupResult(UUID offlineUuid, String summary, int cleanedFiles, boolean cleanedGlobalRefs, Path backupDir) {}

    public static UUID offlineUuid(String name) { return MigrationFiles.offlineUuid(name); }

    public static OfflineData findOfflineData(MinecraftServer server, String name) {
        UUID uuid = offlineUuid(name);
        try {
            String summary = MigrationFiles.summary(root(server), uuid);
            return summary.isEmpty() ? null : new OfflineData(uuid, summary);
        } catch (IOException e) { throw new UncheckedIOException("无法安全检查旧存档，停止继承。", e); }
    }

    public static boolean needsOfflineUpgrade(MinecraftServer server, String name, UUID verifiedUuid) {
        return verifiedUuid != null && !offlineUuid(name).equals(verifiedUuid) && findOfflineData(server, name) != null;
    }

    public static void migrateOfflineToVerified(MinecraftServer server, String name, UUID verifiedUuid) throws IOException {
        migrate(server, name, verifiedUuid, MigrationTransaction.Policy.SAFE);
    }

    public static MigrationTransaction.Result migrate(MinecraftServer server, String name, UUID verifiedUuid,
                                                       MigrationTransaction.Policy policy) throws IOException {
        UUID offline = offlineUuid(name);
        if (verifiedUuid == null || offline.equals(verifiedUuid)) return null;
        if (!server.isSameThread()) throw new IOException("玩家迁移必须在服务端主线程执行。");
        if (server.getPlayerList().getPlayer(offline) != null || server.getPlayerList().getPlayer(verifiedUuid) != null
                || server.getPlayerList().getPlayerByName(name) != null) {
            throw new IOException("新旧账号都必须离线，已停止继承。");
        }
        Path root = root(server);
        MigrationTransaction.Result result = MigrationTransaction.execute(root, name, offline, verifiedUuid, policy,
                PlayerDataMigration::transform, new FtbMigrationCaches(root, offline, verifiedUuid));
        if (result != null) LogUtils.getLogger().info("[TrueUUID] 玩家数据继承完成: player={}, offlineUuid={}, verifiedUuid={}, files={}, sharedFiles={}, backup={}",
                name, offline, verifiedUuid, result.files(), result.sharedFiles(), result.backupDir());
        return result;
    }

    public static CleanupResult cleanupOfflineData(MinecraftServer server, String name) throws IOException {
        UUID verified = TrueuuidRuntime.NAME_REGISTRY.getPremiumUuid(name)
                .orElseThrow(() -> new IOException("没有经过验证的目标 UUID，不能归档唯一旧存档。"));
        OfflineData offline = findOfflineData(server, name);
        if (offline == null) return null;
        MigrationTransaction.Result result = migrate(server, name, verified, MigrationTransaction.Policy.KEEP_VERIFIED);
        return result == null ? null : new CleanupResult(offline.offlineUuid(), offline.summary(), result.files(),
                result.sharedFiles() > 0, result.backupDir());
    }

    public static byte[] transform(MigrationFiles.Format format, byte[] bytes, UUID from, UUID to) throws IOException {
        if (format == MigrationFiles.Format.UUID_TEXT) {
            return MigrationFiles.replaceUuidText(new String(bytes, StandardCharsets.UTF_8), from, to).getBytes(StandardCharsets.UTF_8);
        }
        if (format == MigrationFiles.Format.PLAYER_NBT) {
            CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(bytes));
            // Do not merge/strip inventories or Forge capabilities. Only rebind the entity UUID.
            tag.putUUID("UUID", to);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, output);
            return output.toByteArray();
        }
        return bytes;
    }

    public static Path root(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
    }

    private PlayerDataMigration() {}
}
