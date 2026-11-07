package cn.alini.trueuuid.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/** Back up, atomically apply, archive, and recover one player's known files. */
public final class MigrationTransaction {
    public enum Policy { SAFE, OVERWRITE, KEEP_VERIFIED }
    public record Result(Path backupDir, int files, int sharedFiles) {}
    public interface Transformer {
        byte[] transform(MigrationFiles.Format format, byte[] bytes, UUID from, UUID to) throws IOException;
    }
    public interface CacheHooks {
        default void prepare() throws Exception {}
        default void filesApplied() throws Exception {}
        default void sourcesArchived() throws Exception {}
        default void rollback() throws Exception {}
    }
    public static final CacheHooks NO_CACHES = new CacheHooks() {};
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private record Entry(Path source, Path target, byte[] original, byte[] before, byte[] after, boolean shared) {}

    public static Result execute(Path world, String name, UUID from, UUID to, Policy policy,
                                 Transformer transformer, CacheHooks caches) throws IOException {
        Path root = world.toAbsolutePath().normalize();
        if (from == null || to == null || from.equals(to)) return null;
        Path base = base(root);
        try (FileChannel channel = FileChannel.open(base.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lock(channel)) {
            recoverLocked(root, base);
            try {
                caches.prepare();
                List<Entry> entries = preflight(root, from, to, policy, transformer);
                if (entries.isEmpty()) return null;
                String safeName = name.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT);
                Path backup = Files.createTempDirectory(base, TIME.format(LocalDateTime.now()) + "-" + safeName + "-");
                Properties manifest = prepare(root, backup, name, from, to, entries);
                atomicWrite(base.resolve(".active"), backup.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                try {
                    verifyUnchanged(entries);
                    for (Entry entry : entries) {
                        if (!Arrays.equals(entry.before(), entry.after())) atomicWrite(entry.target(), entry.after());
                    }
                    caches.filesApplied();
                    for (Entry entry : entries) {
                        if (entry.shared()) continue;
                        Path archive = backup.resolve("archived-offline").resolve(root.relativize(entry.source()));
                        Files.createDirectories(archive.getParent());
                        Files.move(entry.source(), archive, StandardCopyOption.ATOMIC_MOVE);
                    }
                    caches.sourcesArchived();
                    manifest.setProperty("state", "COMMITTED");
                    store(backup.resolve("manifest.properties"), manifest);
                    Files.delete(base.resolve(".active"));
                    return new Result(backup, (int) entries.stream().filter(e -> !e.shared()).count(),
                            (int) entries.stream().filter(Entry::shared).count());
                } catch (Exception failure) {
                    try { recoverLocked(root, base); } catch (IOException rollback) { failure.addSuppressed(rollback); }
                    try { caches.rollback(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                    throw new IOException("继承失败，已尝试恢复原档；备份目录: " + backup, failure);
                }
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("无法安全同步相关模组数据，未开始继承。", failure);
            }
        }
    }

    public static void recover(Path world) throws IOException {
        Path root = world.toAbsolutePath().normalize();
        Path base = root.resolve("trueuuid-backups/automatic-migrations");
        checkPath(root, base.resolve(".active"));
        if (!Files.exists(base.resolve(".active"))) return;
        try (FileChannel channel = FileChannel.open(base.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = lock(channel)) {
            recoverLocked(root, base);
        }
    }

    private static List<Entry> preflight(Path root, UUID from, UUID to, Policy policy, Transformer transformer) throws IOException {
        List<MigrationFiles.Pair> pairs = MigrationFiles.pairs(root, from, to);
        List<Entry> entries = new ArrayList<>();
        long total = 0;
        boolean hasSource = pairs.stream().anyMatch(p -> !p.shared() && Files.exists(p.source()));
        if (!hasSource && pairs.stream().noneMatch(MigrationFiles.Pair::shared)) return List.of();
        for (MigrationFiles.Pair pair : pairs) {
            checkPath(root, pair.source());
            checkPath(root, pair.target());
            boolean sourceExists = Files.exists(pair.source());
            boolean targetExists = Files.exists(pair.target());
            if (!sourceExists) {
                if (targetExists && policy == Policy.SAFE) throw conflict(pair.target());
                continue;
            }
            byte[] original = readBounded(pair.source());
            byte[] before = targetExists ? readBounded(pair.target()) : null;
            byte[] expected = transformer.transform(pair.format(), original, from, to);
            if (pair.shared()) {
                String text = new String(original, StandardCharsets.UTF_8);
                String target = to.toString();
                if (text.toLowerCase(Locale.ROOT).contains(target) || text.toLowerCase(Locale.ROOT).contains(target.replace("-", ""))) {
                    throw new IOException("共享数据同时引用新旧 UUID，已保留原档: " + pair.source());
                }
            } else if (policy == Policy.KEEP_VERIFIED) {
                if (!targetExists) throw new IOException("目标缺少对应数据，不能归档唯一旧存档: " + pair.source());
                expected = before;
            } else if (targetExists && policy == Policy.SAFE) {
                byte[] normalizedTarget = transformer.transform(pair.format(), before, from, to);
                if (!Arrays.equals(expected, normalizedTarget)) throw conflict(pair.target());
            }
            total += original.length + expected.length + (before == null ? 0 : before.length);
            if (total > MAX_TOTAL_BYTES) throw new IOException("待继承数据超过自动处理上限，已保留原档。");
            entries.add(new Entry(pair.shared() ? null : pair.source(), pair.target(), original, before, expected, pair.shared()));
        }
        return entries;
    }

    private static IOException conflict(Path target) {
        return new IOException("目标已有不同进度，自动继承不会覆盖；两边数据均保留: " + target);
    }

    private static Properties prepare(Path root, Path backup, String name, UUID from, UUID to, List<Entry> entries) throws IOException {
        Properties manifest = new Properties();
        manifest.setProperty("state", "PREPARED");
        manifest.setProperty("name", name);
        manifest.setProperty("offlineUuid", from.toString());
        manifest.setProperty("verifiedUuid", to.toString());
        manifest.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String key = "entry." + i + ".";
            Path relative = root.relativize(entry.target());
            manifest.setProperty(key + "target", relative.toString());
            manifest.setProperty(key + "afterHash", hash(entry.after()));
            manifest.setProperty(key + "beforeHash", entry.before() == null ? "ABSENT" : hash(entry.before()));
            if (entry.before() != null) atomicWrite(backup.resolve("target-original").resolve(relative), entry.before());
            atomicWrite(backup.resolve("after").resolve(relative), entry.after());
            if (!entry.shared()) {
                Path source = root.relativize(entry.source());
                manifest.setProperty(key + "source", source.toString());
                manifest.setProperty(key + "sourceHash", hash(entry.original()));
                atomicWrite(backup.resolve("source-original").resolve(source), entry.original());
            }
        }
        store(backup.resolve("manifest.properties"), manifest);
        return manifest;
    }

    private static void verifyUnchanged(List<Entry> entries) throws IOException {
        for (Entry entry : entries) {
            if (entry.source() != null && !Arrays.equals(readBounded(entry.source()), entry.original())) {
                throw new IOException("旧存档在预检后发生变化，已停止继承。");
            }
            byte[] current = Files.exists(entry.target()) ? readBounded(entry.target()) : null;
            if (!Arrays.equals(current, entry.before())) throw new IOException("目标存档在预检后发生变化，已停止继承。");
        }
    }

    private static void recoverLocked(Path root, Path base) throws IOException {
        Path active = base.resolve(".active");
        checkPath(root, active);
        if (!Files.exists(active)) return;
        String directory = new String(readBounded(active), StandardCharsets.UTF_8).trim();
        if (directory.isBlank() || directory.contains("/") || directory.contains("\\") || directory.equals("..")) {
            throw new IOException("活动迁移记录无效，停止恢复以保全数据。");
        }
        Path backup = base.resolve(directory);
        checkPath(root, backup.resolve("manifest.properties"));
        Properties manifest = new Properties();
        try (var input = Files.newInputStream(backup.resolve("manifest.properties"))) { manifest.load(input); }
        String state = manifest.getProperty("state", "");
        if (state.equals("COMMITTED") || state.equals("ROLLED_BACK")) { Files.delete(active); return; }
        if (!state.equals("PREPARED")) throw new IOException("迁移状态未知，保留备份: " + backup);
        int count;
        try { count = Integer.parseInt(manifest.getProperty("count")); } catch (RuntimeException e) { throw new IOException("迁移清单无效", e); }
        if (count < 1 || count > 16384) throw new IOException("迁移清单条目数量无效");
        // Validate EVERY file before restoring any file. Never overwrite subsequent gameplay.
        for (int i = 0; i < count; i++) {
            String key = "entry." + i + ".";
            Path target = resolve(root, manifest.getProperty(key + "target"));
            String beforeHash = manifest.getProperty(key + "beforeHash");
            String afterHash = manifest.getProperty(key + "afterHash");
            String current = Files.exists(target) ? hash(readBounded(target)) : "ABSENT";
            if (!current.equals(beforeHash) && !current.equals(afterHash)) {
                throw new IOException("目标在迁移外发生变化，停止自动恢复: " + target + "；备份: " + backup);
            }
            checkedBackup(root, backup.resolve("after").resolve(root.relativize(target)), afterHash);
            if (!"ABSENT".equals(beforeHash)) checkedBackup(root, backup.resolve("target-original").resolve(root.relativize(target)), beforeHash);
            String sourceValue = manifest.getProperty(key + "source");
            if (sourceValue != null) {
                Path source = resolve(root, sourceValue);
                String sourceHash = manifest.getProperty(key + "sourceHash");
                checkedBackup(root, backup.resolve("source-original").resolve(root.relativize(source)), sourceHash);
                if (Files.exists(source) && !hash(readBounded(source)).equals(sourceHash)) {
                    throw new IOException("旧存档在迁移外发生变化，停止自动恢复: " + source);
                }
            }
        }
        for (int i = 0; i < count; i++) {
            String key = "entry." + i + ".";
            Path target = resolve(root, manifest.getProperty(key + "target"));
            if ("ABSENT".equals(manifest.getProperty(key + "beforeHash"))) Files.deleteIfExists(target);
            else atomicWrite(target, readBounded(backup.resolve("target-original").resolve(root.relativize(target))));
            String sourceValue = manifest.getProperty(key + "source");
            if (sourceValue != null) {
                Path source = resolve(root, sourceValue);
                if (!Files.exists(source)) atomicWrite(source, readBounded(backup.resolve("source-original").resolve(root.relativize(source))));
            }
        }
        manifest.setProperty("state", "ROLLED_BACK");
        store(backup.resolve("manifest.properties"), manifest);
        Files.delete(active);
    }

    private static void checkedBackup(Path root, Path backup, String expected) throws IOException {
        checkPath(root, backup);
        if (expected == null || !hash(readBounded(backup)).equals(expected)) throw new IOException("迁移备份摘要不匹配: " + backup);
    }

    private static Path resolve(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank() || Path.of(relative).isAbsolute()) throw new IOException("迁移路径无效");
        Path result = root.resolve(relative).normalize();
        checkPath(root, result);
        return result;
    }

    public static void checkPath(Path root, Path path) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path value = path.toAbsolutePath().normalize();
        if (!value.startsWith(base)) throw new IOException("迁移路径超出存档目录: " + path);
        for (Path p = value; p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p)) throw new IOException("迁移不接受符号链接: " + p);
            if (p.equals(base)) break;
        }
    }

    public static byte[] readBounded(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("数据不是普通文件或超过迁移上限: " + path);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes((int) MAX_FILE_BYTES + 1);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("数据在读取时超过迁移上限: " + path);
            return bytes;
        }
    }

    private static Path base(Path root) throws IOException {
        Path result = root.resolve("trueuuid-backups/automatic-migrations");
        checkPath(root, result.resolve(".lock"));
        Files.createDirectories(result);
        return result;
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("另一项玩家迁移正在进行，请稍后重试。");
            return lock;
        } catch (OverlappingFileLockException e) { throw new IOException("另一项玩家迁移正在进行，请稍后重试。", e); }
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".trueuuid-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static void store(Path target, Properties properties) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        properties.store(output, "TrueUUID migration; retain all backup files");
        atomicWrite(target, output.toByteArray());
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private MigrationTransaction() {}
}
