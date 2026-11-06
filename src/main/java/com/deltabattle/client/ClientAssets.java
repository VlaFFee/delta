package com.deltabattle.client;

import com.deltabattle.DeltaBattle;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Библиотека файлов моделей для редактора: список geo/animation/текстур/музыки из ресурсов и импорт файлов,
 * перетащенных в окно игры. Импортированное копируется в папку игры deltabattle_assets (автоматически
 * подключаемый ресурс-пак) и ресурсы перезагружаются. Чтобы увидели другие игроки, им нужны те же файлы.
 */
public final class ClientAssets {
    private static final Map<String, List<String>> CACHE = new HashMap<>();
    private static final Map<String, JsonObject> ANIMS = new HashMap<>();

    public static Path userDir() { return FMLPaths.GAMEDIR.get().resolve("deltabattle_assets"); }

    /** Создаёт папку пака и pack.mcmeta, если их нет. */
    public static void ensurePack() {
        try {
            Path root = userDir();
            Files.createDirectories(root.resolve("assets").resolve(DeltaBattle.MODID));
            Path meta = root.resolve("pack.mcmeta");
            if (!Files.exists(meta)) Files.writeString(meta, "{\"pack\":{\"description\":\"DeltaBattle imported assets\",\"pack_format\":15}}", StandardCharsets.UTF_8);
        } catch (IOException e) { DeltaBattle.LOGGER.error("Cannot create asset pack", e); }
    }

    public static void refresh() { CACHE.clear(); ANIMS.clear(); }

    // ---------- списки ----------
    private static List<String> list(String dir, String suffix, String... prefixes) {
        String key = dir + suffix + String.join(",", prefixes);
        return CACHE.computeIfAbsent(key, k -> {
            List<String> out = new ArrayList<>();
            var rm = Minecraft.getInstance().getResourceManager();
            for (ResourceLocation rl : rm.listResources(dir, r -> r.getPath().endsWith(suffix)).keySet()) {
                if (prefixes.length > 0) {
                    if (rl.getNamespace().equals("minecraft")) continue;
                    boolean ok = false;
                    for (String p : prefixes) if (rl.getPath().startsWith(p)) ok = true;
                    if (!ok) continue;
                }
                out.add(rl.toString());
            }
            Collections.sort(out);
            return out;
        });
    }

    public static List<String> geo() { return list("geo", ".geo.json"); }
    public static List<String> animFiles() { return list("animations", ".animation.json"); }
    public static List<String> textures() { return list("textures", ".png", "textures/npc/", "textures/entity/", "textures/gui/", "textures/models/"); }

    public static List<String> musicIds() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 6; i++) out.add(DeltaBattle.MODID + ":music.battle" + i);
        try {
            Path sj = userDir().resolve("assets").resolve(DeltaBattle.MODID).resolve("sounds.json");
            if (Files.exists(sj))
                for (String k : JsonParser.parseString(Files.readString(sj, StandardCharsets.UTF_8)).getAsJsonObject().keySet()) {
                    String id = DeltaBattle.MODID + ":" + k;
                    if (!out.contains(id)) out.add(id);
                }
        } catch (Exception ignored) { }
        out.addAll(List.of("minecraft:music_disc.pigstep", "minecraft:music_disc.otherside", "minecraft:music_disc.cat", "minecraft:music_disc.chirp"));
        return out;
    }

    // ---------- анимации из animation.json ----------
    private static JsonObject animJson(String file) {
        if (file == null || file.isEmpty()) return null;
        return ANIMS.computeIfAbsent(file, f -> {
            ResourceLocation rl = ResourceLocation.tryParse(f);
            if (rl == null) return new JsonObject();
            Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(rl);
            if (r.isEmpty()) return new JsonObject();
            try (Reader rd = new InputStreamReader(r.get().open(), StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(rd);
                JsonObject o = e.getAsJsonObject();
                return o.has("animations") ? o.getAsJsonObject("animations") : new JsonObject();
            } catch (Exception ex) { return new JsonObject(); }
        });
    }

    /** Имена анимаций из файла animation.json (например animation.knight.idle). */
    public static List<String> animNames(String file) {
        JsonObject o = animJson(file);
        List<String> out = new ArrayList<>();
        if (o != null) out.addAll(o.keySet());
        Collections.sort(out);
        return out;
    }

    /** Длительность анимации в миллисекундах (поле animation_length), по умолчанию 800. */
    public static long animMillis(String file, String name) {
        JsonObject o = animJson(file);
        try {
            if (o != null && o.has(name) && o.getAsJsonObject(name).has("animation_length"))
                return Math.round(o.getAsJsonObject(name).get("animation_length").getAsDouble() * 1000.0) + 50;
        } catch (Exception ignored) { }
        return 800;
    }

    // ---------- импорт ----------
    private static String clean(String n) { return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_"); }

    /** Копирует перетащенные файлы в пак. Возвращает количество принятых файлов; ресурсы перезагружаются. */
    public static int importFiles(List<Path> files) {
        ensurePack();
        Path root = userDir().resolve("assets").resolve(DeltaBattle.MODID);
        int n = 0;
        for (Path f : files) {
            String name = clean(f.getFileName().toString());
            Path dest;
            String musicBase = null;
            if (name.endsWith(".geo.json")) dest = root.resolve("geo").resolve(name);
            else if (name.endsWith(".animation.json")) dest = root.resolve("animations").resolve(name);
            else if (name.endsWith(".png")) dest = root.resolve("textures").resolve("npc").resolve(name);
            else if (name.endsWith(".ogg")) { dest = root.resolve("sounds").resolve("music").resolve(name); musicBase = name.substring(0, name.length() - 4); }
            else continue;
            try {
                Files.createDirectories(dest.getParent());
                Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
                if (musicBase != null) registerSound(root, musicBase);
                n++;
            } catch (IOException e) { DeltaBattle.LOGGER.error("Import failed: {}", f, e); }
        }
        if (n > 0) reload();
        return n;
    }

    /** Добавляет событие music.<имя> в sounds.json пака. */
    private static void registerSound(Path root, String base) throws IOException {
        Path sj = root.resolve("sounds.json");
        JsonObject o = Files.exists(sj) ? JsonParser.parseString(Files.readString(sj, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
        JsonObject ev = new JsonObject();
        ev.addProperty("category", "music");
        JsonObject snd = new JsonObject();
        snd.addProperty("name", DeltaBattle.MODID + ":music/" + base);
        snd.addProperty("stream", true);
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(snd);
        ev.add("sounds", arr);
        o.add("music." + base, ev);
        Files.writeString(sj, o.toString(), StandardCharsets.UTF_8);
    }

    public static void reload() {
        Minecraft.getInstance().reloadResourcePacks().thenRun(ClientAssets::refresh);
    }
}
