package com.deltabattle.data;

import com.deltabattle.DeltaBattle;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Битвы из датапаков (data/&lt;ns&gt;/encounters/*.json, перечитываются /reload)
 * и битвы из редактора (папка мира deltabattle/encounters/&lt;ns&gt;/&lt;имя&gt;.json). Битвы мира важнее датапака.
 */
public class EncounterManager extends SimpleJsonResourceReloadListener {
    public static final EncounterManager INSTANCE = new EncounterManager();
    private static volatile Map<ResourceLocation, Encounter> MAP = Map.of();
    private static volatile Map<ResourceLocation, Encounter> WORLD = Map.of();
    private static volatile Path worldDir;

    private EncounterManager() { super(new Gson(), "encounters"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> in, ResourceManager rm, ProfilerFiller profiler) {
        Map<ResourceLocation, Encounter> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : in.entrySet()) {
            try {
                out.put(e.getKey(), Encounter.GSON.fromJson(e.getValue(), Encounter.class).normalize(e.getKey().toString()));
            } catch (Exception ex) {
                DeltaBattle.LOGGER.error("Bad encounter {}", e.getKey(), ex);
            }
        }
        MAP = out;
        DeltaBattle.LOGGER.info("Loaded {} datapack encounters", out.size());
    }

    // ---------- битвы из редактора (папка мира) ----------
    public static void loadWorld(MinecraftServer server) {
        worldDir = server.getWorldPath(LevelResource.ROOT).resolve("deltabattle").resolve("encounters");
        Map<ResourceLocation, Encounter> out = new HashMap<>();
        if (Files.isDirectory(worldDir)) {
            try (Stream<Path> st = Files.walk(worldDir)) {
                for (Path f : (Iterable<Path>) st.filter(x -> x.toString().endsWith(".json"))::iterator) {
                    try {
                        Path rel = worldDir.relativize(f);
                        String ns = rel.getName(0).toString();
                        String path = rel.subpath(1, rel.getNameCount()).toString().replace('\\', '/');
                        path = path.substring(0, path.length() - 5);
                        ResourceLocation rl = ResourceLocation.tryParse(ns + ":" + path);
                        if (rl == null) continue;
                        out.put(rl, Encounter.GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Encounter.class).normalize(rl.toString()));
                    } catch (Exception ex) {
                        DeltaBattle.LOGGER.error("Bad world encounter {}", f, ex);
                    }
                }
            } catch (IOException ex) {
                DeltaBattle.LOGGER.error("Cannot read world encounters", ex);
            }
        }
        WORLD = out;
        DeltaBattle.LOGGER.info("Loaded {} world encounters from {}", out.size(), worldDir);
    }

    /** Сохраняет битву в папку мира. Возвращает ошибку или null. */
    public static String save(ResourceLocation rl, Encounter e) {
        Path dir = worldDir;
        if (dir == null) return "World is not loaded";
        if (rl.getPath().contains("..")) return "Bad id";
        try {
            Path f = dir.resolve(rl.getNamespace()).resolve(rl.getPath() + ".json").normalize();
            if (!f.startsWith(dir)) return "Bad id";
            Files.createDirectories(f.getParent());
            Files.writeString(f, Encounter.PRETTY.toJson(e), StandardCharsets.UTF_8);
            Map<ResourceLocation, Encounter> m = new HashMap<>(WORLD);
            m.put(rl, e);
            WORLD = m;
            return null;
        } catch (IOException ex) {
            return ex.getMessage();
        }
    }

    /** Удаляет битву из папки мира (битвы датапака не трогает). */
    public static boolean delete(ResourceLocation rl) {
        Path dir = worldDir;
        if (dir == null || rl.getPath().contains("..")) return false;
        try {
            Path f = dir.resolve(rl.getNamespace()).resolve(rl.getPath() + ".json").normalize();
            if (!f.startsWith(dir)) return false;
            boolean ok = Files.deleteIfExists(f);
            Map<ResourceLocation, Encounter> m = new HashMap<>(WORLD);
            m.remove(rl);
            WORLD = m;
            return ok;
        } catch (IOException ex) {
            return false;
        }
    }

    // ---------- доступ ----------
    /** "jeep" -> deltabattle:jeep (или custom:jeep); "ns:name" как есть. */
    public static Encounter get(String id) {
        ResourceLocation rl = parse(id);
        return rl == null ? null : get(rl);
    }

    public static Encounter get(ResourceLocation rl) {
        Encounter e = find(rl);
        if (e == null && rl.getNamespace().equals("minecraft")) {
            e = find(new ResourceLocation(DeltaBattle.MODID, rl.getPath()));
            if (e == null) e = find(new ResourceLocation("custom", rl.getPath()));
        }
        return e;
    }

    private static Encounter find(ResourceLocation rl) {
        Encounter e = WORLD.get(rl);
        return e != null ? e : MAP.get(rl);
    }

    /** Без двоеточия: сначала deltabattle:, затем custom:. */
    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.indexOf(':') >= 0) return ResourceLocation.tryParse(id);
        ResourceLocation a = new ResourceLocation(DeltaBattle.MODID, id);
        return find(a) != null ? a : ResourceLocation.tryParse("custom:" + id);
    }

    public static Collection<ResourceLocation> ids() {
        Set<ResourceLocation> s = new TreeSet<>(MAP.keySet());
        s.addAll(WORLD.keySet());
        return s;
    }
}
