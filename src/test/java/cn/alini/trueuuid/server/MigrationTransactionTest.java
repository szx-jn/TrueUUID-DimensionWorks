package cn.alini.trueuuid.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MigrationTransactionTest {
    @TempDir Path root;
    private static final UUID OLD = MigrationFiles.offlineUuid("GAOFEI");
    private static final UUID NEW = UUID.fromString("833425ab-68fc-4beb-9214-4def06e71e06");
    private static final UUID OTHER = UUID.fromString("14f5a274-4bad-418b-a1b8-077729535f5c");
    private static final MigrationTransaction.Transformer FILES = (format, bytes, from, to) ->
            format == MigrationFiles.Format.UUID_TEXT
                    ? MigrationFiles.replaceUuidText(new String(bytes, StandardCharsets.UTF_8), from, to).getBytes(StandardCharsets.UTF_8)
                    : bytes;

    @Test
    void migratesAllKnownFilesAndSharedReferencesThenArchivesAndDoesNotRepeat() throws Exception {
        Map<Path, byte[]> original = seedAll();
        Path party = write("ftbteams/party/" + OTHER + ".snbt", "{owner:\"" + OLD + "\", ally:\"" + OTHER + "\"}");
        Path quests = write("ftbquests/" + OTHER + ".snbt", "{claimed_rewards:{\"" + OLD.toString().replace("-", "") + ":reward\":123L}}");
        Path ranks = write("serverconfig/ftbranks/players.snbt", "{players:{\"" + OLD + "\":\"member\"}}");
        var result = execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES);
        assertNotNull(result);
        assertEquals(12, result.files());
        assertEquals(3, result.sharedFiles());
        for (var spec : MigrationFiles.SPECS) {
            Path source = MigrationFiles.path(root, spec, OLD);
            Path target = MigrationFiles.path(root, spec, NEW);
            assertFalse(Files.exists(source), source.toString());
            assertArrayEquals(FILES.transform(spec.format(), original.get(source), OLD, NEW), Files.readAllBytes(target));
            assertArrayEquals(original.get(source), Files.readAllBytes(result.backupDir().resolve("source-original").resolve(root.relativize(source))));
            assertArrayEquals(original.get(source), Files.readAllBytes(result.backupDir().resolve("archived-offline").resolve(root.relativize(source))));
        }
        assertTrue(Files.readString(party).contains(NEW.toString()));
        assertTrue(Files.readString(party).contains(OTHER.toString()));
        assertTrue(Files.readString(quests).contains(NEW.toString().replace("-", "")));
        assertTrue(Files.readString(ranks).contains(NEW.toString()));
        assertEquals("COMMITTED", state(result.backupDir()));
        assertFalse(Files.exists(active()));
        assertNull(execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
        assertEquals(1, transactions().size());
    }

    @Test
    void differentTargetProgressIsNotOverwrittenOrPartiallyMigrated() throws Exception {
        seedAll();
        Path target = write("playerdata/" + NEW + ".dat", "new-progress");
        Map<Path, byte[]> before = snapshotData();
        IOException failure = assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
        assertTrue(failure.getMessage().contains("不同进度"));
        assertDataEquals(before);
        assertEquals("new-progress", Files.readString(target));
        assertTrue(transactions().isEmpty());
    }

    @Test
    void alreadyCopiedIdenticalDataCanBeArchivedWithoutOverwritingProgress() throws Exception {
        Map<Path, byte[]> source = seedAll();
        for (var spec : MigrationFiles.SPECS) {
            Path old = MigrationFiles.path(root, spec, OLD);
            Path target = MigrationFiles.path(root, spec, NEW);
            write(target, FILES.transform(spec.format(), source.get(old), OLD, NEW));
        }
        var result = execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES);
        assertEquals(12, result.files());
        for (var spec : MigrationFiles.SPECS) assertFalse(Files.exists(MigrationFiles.path(root, spec, OLD)));
        assertEquals("COMMITTED", state(result.backupDir()));
    }

    @Test
    void targetDataWithoutCorrespondingSourceStopsMixingTwoSaves() throws Exception {
        write("playerdata/" + OLD + ".dat", "same");
        write("playerdata/" + NEW + ".dat", "same");
        write("stats/" + NEW + ".json", "{\"new-progress\":100}");
        Map<Path, byte[]> before = snapshotData();
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
        assertDataEquals(before);
    }

    @Test
    void cacheRefreshFailureRestoresBothOriginalTargetsAndSources() throws Exception {
        seedAll();
        write("playerdata/" + NEW + ".dat", "original-target");
        Path global = write("ftbteams/party/" + OTHER + ".snbt", "{owner:\"" + OLD + "\"}");
        Map<Path, byte[]> before = snapshotData();
        AtomicBoolean rolledBack = new AtomicBoolean();
        var cache = new MigrationTransaction.CacheHooks() {
            @Override public void filesApplied() throws IOException {
                assertTrue(Files.readString(global).contains(NEW.toString()));
                throw new IOException("simulated cache incompatibility");
            }
            @Override public void rollback() { rolledBack.set(true); }
        };
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.OVERWRITE, cache));
        assertTrue(rolledBack.get());
        assertDataEquals(before);
        assertEquals("ROLLED_BACK", state(transactions().get(0)));
        assertFalse(Files.exists(active()));
    }

    @Test
    void failureAfterArchivingRestoresSourcesAndKeepsBackupBytes() throws Exception {
        Map<Path, byte[]> before = seedAll();
        var cache = new MigrationTransaction.CacheHooks() {
            @Override public void sourcesArchived() throws IOException {
                assertFalse(Files.exists(root.resolve("playerdata/" + OLD + ".dat")));
                throw new IOException("simulated finalization failure");
            }
        };
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, cache));
        assertDataEquals(before);
        Path transaction = transactions().get(0);
        assertEquals("ROLLED_BACK", state(transaction));
        assertTrue(Files.exists(transaction.resolve("archived-offline/playerdata/" + OLD + ".dat")));
    }

    @Test
    void stoppedProcessTransactionIsRecoveredBeforeAWorldIsLoaded() throws Exception {
        seedAll();
        write("playerdata/" + NEW + ".dat", "old-target");
        Map<Path, byte[]> before = snapshotData();
        var cache = new MigrationTransaction.CacheHooks() {
            @Override public void sourcesArchived() { throw new SimulatedProcessExit(); }
        };
        assertThrows(SimulatedProcessExit.class, () -> execute(MigrationTransaction.Policy.OVERWRITE, cache));
        assertTrue(Files.exists(active()));
        assertFalse(Files.exists(root.resolve("playerdata/" + OLD + ".dat")));
        MigrationTransaction.recover(root);
        assertDataEquals(before);
        assertFalse(Files.exists(active()));
        assertEquals("ROLLED_BACK", state(transactions().get(0)));
    }

    @Test
    void recoveryDoesNotOverwriteLaterChanges() throws Exception {
        seedAll();
        var cache = new MigrationTransaction.CacheHooks() {
            @Override public void sourcesArchived() { throw new SimulatedProcessExit(); }
        };
        assertThrows(SimulatedProcessExit.class, () -> execute(MigrationTransaction.Policy.SAFE, cache));
        Path target = write("playerdata/" + NEW + ".dat", "subsequent-progress");
        Map<Path, byte[]> beforeRecovery = snapshotData();
        assertThrows(IOException.class, () -> MigrationTransaction.recover(root));
        assertDataEquals(beforeRecovery);
        assertEquals("subsequent-progress", Files.readString(target));
        assertTrue(Files.exists(active()));
        assertEquals("PREPARED", state(transactions().get(0)));
    }

    @Test
    void symbolicallyLinkedParentIsRefusedWithoutTouchingOutsideFiles() throws Exception {
        Path outside = Files.createTempDirectory("trueuuid-outside-");
        try {
            Path data = outside.resolve(OLD + ".dat");
            Files.writeString(data, "outside");
            Files.createSymbolicLink(root.resolve("playerdata"), outside);
            assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
            assertEquals("outside", Files.readString(data));
            assertFalse(Files.exists(outside.resolve(NEW + ".dat")));
        } finally {
            Files.deleteIfExists(root.resolve("playerdata"));
            Files.deleteIfExists(outside.resolve(OLD + ".dat"));
            Files.delete(outside);
        }
    }

    @Test
    void cleanupCannotArchiveTheOnlyCopyOfAnyDataType() throws Exception {
        seedAll();
        write("playerdata/" + NEW + ".dat", "selected-target");
        Map<Path, byte[]> before = snapshotData();
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.KEEP_VERIFIED, MigrationTransaction.NO_CACHES));
        assertDataEquals(before);
    }

    @Test
    void explicitKeepVerifiedArchivesOldFilesAndPreservesSelectedTargets() throws Exception {
        seedAll();
        for (var spec : MigrationFiles.SPECS) write(MigrationFiles.path(root, spec, NEW), "selected-" + spec.label());
        var result = execute(MigrationTransaction.Policy.KEEP_VERIFIED, MigrationTransaction.NO_CACHES);
        for (var spec : MigrationFiles.SPECS) {
            assertEquals("selected-" + spec.label(), Files.readString(MigrationFiles.path(root, spec, NEW)));
            assertFalse(Files.exists(MigrationFiles.path(root, spec, OLD)));
        }
        assertEquals("COMMITTED", state(result.backupDir()));
    }

    @Test
    void sharedReferencesWithBothIdentitiesAreNotBlindlyMerged() throws Exception {
        seedAll();
        write("ftbteams/party/" + OTHER + ".snbt", "{ranks:{\"" + OLD + "\":\"member\",\"" + NEW + "\":\"owner\"}}");
        Map<Path, byte[]> before = snapshotData();
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
        assertDataEquals(before);
    }

    @Test
    void oversizedSourceIsRefusedBeforeAnyWrites() throws Exception {
        Path source = root.resolve("playerdata/" + OLD + ".dat");
        Files.createDirectories(source.getParent());
        try (FileChannel file = FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            file.position(MigrationTransaction.MAX_FILE_BYTES);
            file.write(ByteBuffer.wrap(new byte[]{1}));
        }
        assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
        assertEquals(MigrationTransaction.MAX_FILE_BYTES + 1, Files.size(source));
        assertFalse(Files.exists(root.resolve("playerdata/" + NEW + ".dat")));
    }

    @Test
    void concurrentMigrationsAreRefusedInsteadOfSharingBackupOrWritingTwice() throws Exception {
        seedAll();
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> execute(MigrationTransaction.Policy.SAFE, new MigrationTransaction.CacheHooks() {
                @Override public void prepare() throws InterruptedException {
                    acquired.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
            }));
            assertTrue(acquired.await(10, TimeUnit.SECONDS));
            assertThrows(IOException.class, () -> execute(MigrationTransaction.Policy.SAFE, MigrationTransaction.NO_CACHES));
            release.countDown();
            assertNotNull(first.get(10, TimeUnit.SECONDS));
            assertEquals(1, transactions().size());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void uuidRewritesHandleBothSpellingsAndKeepUnrelatedTokens() {
        String old = OLD.toString();
        String compact = old.replace("-", "");
        String text = old.toUpperCase() + " " + compact.toUpperCase() + " a" + old + " " + OTHER;
        String rewritten = MigrationFiles.replaceUuidText(text, OLD, NEW);
        assertTrue(rewritten.startsWith(NEW + " " + NEW.toString().replace("-", "")));
        assertTrue(rewritten.contains("a" + old));
        assertTrue(rewritten.contains(OTHER.toString()));
        assertEquals(UUID.fromString("5307234e-0365-3427-bae7-6d2e78079831"), OLD);
    }

    private MigrationTransaction.Result execute(MigrationTransaction.Policy policy, MigrationTransaction.CacheHooks hooks) throws IOException {
        return MigrationTransaction.execute(root, "GAOFEI", OLD, NEW, policy, FILES, hooks);
    }

    private Map<Path, byte[]> seedAll() throws IOException {
        for (var spec : MigrationFiles.SPECS) {
            String value = spec.format() == MigrationFiles.Format.UUID_TEXT
                    ? "{id:\"" + OLD + "\",progress:23,other:\"" + OTHER + "\"}"
                    : "opaque-payload-" + spec.directory() + spec.suffix();
            write(MigrationFiles.path(root, spec, OLD), value);
        }
        return snapshotData();
    }

    private Path write(String relative, String value) throws IOException { return write(root.resolve(relative), value); }
    private Path write(Path path, String value) throws IOException { return write(path, value.getBytes(StandardCharsets.UTF_8)); }
    private Path write(Path path, byte[] value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, value);
        return path;
    }

    private Map<Path, byte[]> snapshotData() throws IOException {
        Map<Path, byte[]> result = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.startsWith(root.resolve("trueuuid-backups"))) result.put(path, Files.readAllBytes(path));
            }
        }
        return result;
    }

    private void assertDataEquals(Map<Path, byte[]> before) throws IOException {
        Map<Path, byte[]> after = snapshotData();
        assertEquals(before.keySet(), after.keySet());
        for (Path path : before.keySet()) assertArrayEquals(before.get(path), after.get(path), path.toString());
    }

    private Path active() { return root.resolve("trueuuid-backups/automatic-migrations/.active"); }
    private List<Path> transactions() throws IOException {
        Path base = active().getParent();
        if (!Files.exists(base)) return List.of();
        try (var paths = Files.list(base)) { return paths.filter(Files::isDirectory).sorted().toList(); }
    }
    private static String state(Path transaction) throws IOException {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(transaction.resolve("manifest.properties"))) { properties.load(input); }
        return properties.getProperty("state");
    }
    private static final class SimulatedProcessExit extends Error {}
}
