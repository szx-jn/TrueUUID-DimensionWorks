package cn.alini.trueuuid.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerNbtMigrationTest {
    @TempDir Path root;
    private static final UUID OLD = MigrationFiles.offlineUuid("1q_b1");
    private static final UUID NEW = UUID.fromString("51b1696c-c4e3-44d9-b7c0-2706b9795eff");

    @Test
    void rebindsOnlyRootUuidAndPreservesInventoryEnderPositionXpAndForgeCaps() throws Exception {
        CompoundTag original = player();
        byte[] source = bytes(original);
        byte[] migrated = PlayerDataMigration.transform(MigrationFiles.Format.PLAYER_NBT, source, OLD, NEW);
        CompoundTag after = NbtIo.readCompressed(new ByteArrayInputStream(migrated));
        assertEquals(NEW, after.getUUID("UUID"));
        CompoundTag expected = original.copy();
        expected.putUUID("UUID", NEW);
        assertEquals(expected, after);
        assertEquals(4, after.getList("Inventory", 10).size());
        assertEquals(1, after.getList("EnderItems", 10).size());
        assertEquals(17, after.getCompound("ForgeCaps").size());
        assertEquals(OLD, NbtIo.readCompressed(new ByteArrayInputStream(source)).getUUID("UUID"));
    }

    @Test
    void completeTransactionPreservesOriginalCompressedBytesAndOldSlotBackup() throws Exception {
        byte[] dat = bytes(player());
        CompoundTag previous = player();
        previous.putInt("XpLevel", 7);
        byte[] old = bytes(previous);
        Files.createDirectories(root.resolve("playerdata"));
        Path source = root.resolve("playerdata/" + OLD + ".dat");
        Path sourceOld = root.resolve("playerdata/" + OLD + ".dat_old");
        Files.write(source, dat);
        Files.write(sourceOld, old);
        var result = MigrationTransaction.execute(root, "1q_b1", OLD, NEW, MigrationTransaction.Policy.SAFE,
                PlayerDataMigration::transform, MigrationTransaction.NO_CACHES);
        assertNotNull(result);
        assertEquals(2, result.files());
        CompoundTag expected = player();
        expected.putUUID("UUID", NEW);
        assertEquals(expected, NbtIo.readCompressed(Files.newInputStream(root.resolve("playerdata/" + NEW + ".dat"))));
        assertEquals(7, NbtIo.readCompressed(Files.newInputStream(root.resolve("playerdata/" + NEW + ".dat_old"))).getInt("XpLevel"));
        assertArrayEquals(dat, Files.readAllBytes(result.backupDir().resolve("archived-offline/playerdata/" + OLD + ".dat")));
        assertArrayEquals(old, Files.readAllBytes(result.backupDir().resolve("archived-offline/playerdata/" + OLD + ".dat_old")));
    }

    @Test
    void corruptCompressedPlayerDataIsRefusedWithoutRenamingOrOverwriting() throws Exception {
        Files.createDirectories(root.resolve("playerdata"));
        Path source = root.resolve("playerdata/" + OLD + ".dat");
        byte[] original = new byte[]{1, 2, 3, 4};
        Files.write(source, original);
        assertThrows(java.io.IOException.class, () -> MigrationTransaction.execute(root, "1q_b1", OLD, NEW,
                MigrationTransaction.Policy.SAFE, PlayerDataMigration::transform, MigrationTransaction.NO_CACHES));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertFalse(Files.exists(root.resolve("playerdata/" + NEW + ".dat")));
    }

    private static byte[] bytes(CompoundTag tag) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        return output.toByteArray();
    }

    private static CompoundTag player() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("UUID", OLD);
        tag.putFloat("Health", 20);
        tag.putInt("XpLevel", 1);
        tag.putString("Dimension", "minecraft:overworld");
        ListTag position = new ListTag();
        position.add(DoubleTag.valueOf(-324.2683021748818));
        position.add(DoubleTag.valueOf(-1.0676061117192768));
        position.add(DoubleTag.valueOf(2066.53113752947));
        tag.put("Pos", position);
        ListTag inventory = new ListTag();
        String[] items = {"create_mobile_packages:mobile_packager", "create:wrench", "ae2wtlib:wireless_universal_terminal", "minecraft:cobblestone"};
        for (int i = 0; i < items.length; i++) {
            CompoundTag item = new CompoundTag();
            item.putByte("Slot", (byte) i);
            item.putString("id", items[i]);
            item.putByte("Count", (byte) (i == 3 ? 32 : 1));
            CompoundTag extra = new CompoundTag();
            extra.putString("custom_data", "keep-all-nested-item-data");
            item.put("tag", extra);
            inventory.add(item);
        }
        tag.put("Inventory", inventory);
        ListTag ender = new ListTag();
        ender.add(inventory.getCompound(2).copy());
        tag.put("EnderItems", ender);
        CompoundTag caps = new CompoundTag();
        for (int i = 0; i < 17; i++) {
            CompoundTag cap = new CompoundTag();
            cap.putInt("value", i);
            cap.putUUID("other_owner", OLD);
            caps.put("test:cap_" + i, cap);
        }
        tag.put("ForgeCaps", caps);
        return tag;
    }
}
