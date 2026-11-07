package cn.alini.trueuuid.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Known player paths only; never walks region files or loads chunks. */
public final class MigrationFiles {
    public enum Format { BINARY, UUID_TEXT, PLAYER_NBT }
    public record Spec(String directory, String suffix, Format format, String label) {}
    public record Pair(Path source, Path target, Format format, boolean shared) {}

    public static final List<Spec> SPECS = List.of(
            new Spec("playerdata", ".dat", Format.PLAYER_NBT, "playerdata"),
            new Spec("playerdata", ".dat_old", Format.PLAYER_NBT, "playerdata_old"),
            new Spec("playerdata", ".cosarmor", Format.BINARY, "Cosmetic Armor"),
            new Spec("advancements", ".json", Format.BINARY, "advancements"),
            new Spec("stats", ".json", Format.BINARY, "stats"),
            new Spec("data/openpartiesandclaims/player-claims", ".nbt", Format.BINARY, "Open Parties and Claims"),
            new Spec("data/openpartiesandclaims/player-configs", ".toml", Format.UUID_TEXT, "Open Parties and Claims"),
            new Spec("ftbchunks", ".snbt", Format.UUID_TEXT, "FTB Chunks"),
            new Spec("ftbessentials/playerdata", ".snbt", Format.UUID_TEXT, "FTB Essentials"),
            new Spec("ftbteams/player", ".snbt", Format.UUID_TEXT, "FTB Teams"),
            new Spec("ftbquests", ".snbt", Format.UUID_TEXT, "FTB Quests"),
            new Spec("customnpcs/playerdata", ".json", Format.UUID_TEXT, "CustomNPCs"));
    private static final List<String> SHARED_DIRECTORIES = List.of(
            "ftbteams/player", "ftbteams/party", "ftbteams/server", "ftbquests", "ftbchunks");

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    public static List<Pair> pairs(Path root, UUID from, UUID to) throws IOException {
        List<Pair> result = new ArrayList<>();
        for (Spec spec : SPECS) {
            result.add(new Pair(path(root, spec, from), path(root, spec, to), spec.format(), false));
        }
        for (String directory : SHARED_DIRECTORIES) {
            Path dir = root.resolve(directory);
            MigrationTransaction.checkPath(root, dir);
            if (!Files.exists(dir)) continue;
            try (var files = Files.list(dir)) {
                List<Path> paths = files.filter(p -> p.getFileName().toString().endsWith(".snbt")).sorted().toList();
                if (paths.size() > 4096) throw new IOException("相关 FTB 数据文件过多，已停止自动继承。");
                for (Path file : paths) {
                    if (result.stream().anyMatch(p -> p.source().equals(file) || p.target().equals(file))) continue;
                    if (containsUuid(root, file, from)) result.add(new Pair(file, file, Format.UUID_TEXT, true));
                }
            }
        }
        Path ranks = root.resolve("serverconfig/ftbranks/players.snbt");
        if (containsUuid(root, ranks, from)) result.add(new Pair(ranks, ranks, Format.UUID_TEXT, true));
        return List.copyOf(result);
    }

    public static Path path(Path root, Spec spec, UUID uuid) {
        return root.resolve(spec.directory()).resolve(uuid + spec.suffix());
    }

    public static String summary(Path root, UUID uuid) throws IOException {
        List<String> labels = new ArrayList<>();
        for (Spec spec : SPECS) {
            Path path = path(root, spec, uuid);
            MigrationTransaction.checkPath(root, path);
            if (Files.exists(path) && !labels.contains(spec.label())) labels.add(spec.label());
        }
        if (containsUuid(root, root.resolve("serverconfig/ftbranks/players.snbt"), uuid)) labels.add("FTB Ranks");
        return String.join(", ", labels);
    }

    public static boolean containsUuid(Path root, Path file, UUID uuid) throws IOException {
        MigrationTransaction.checkPath(root, file);
        if (!Files.exists(file)) return false;
        String text = new String(MigrationTransaction.readBounded(file), StandardCharsets.UTF_8);
        return pattern(uuid, false).matcher(text).find() || pattern(uuid, true).matcher(text).find();
    }

    public static String replaceUuidText(String text, UUID from, UUID to) {
        return pattern(from, true).matcher(pattern(from, false).matcher(text).replaceAll(to.toString()))
                .replaceAll(to.toString().replace("-", ""));
    }

    private static Pattern pattern(UUID uuid, boolean compact) {
        String value = uuid.toString();
        if (compact) value = value.replace("-", "");
        return Pattern.compile("(?i)(?<![0-9a-f])" + Pattern.quote(value) + "(?![0-9a-f])");
    }

    private MigrationFiles() {}
}
