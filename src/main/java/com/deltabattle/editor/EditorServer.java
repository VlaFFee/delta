package com.deltabattle.editor;

import com.deltabattle.battle.BattleManager;
import com.deltabattle.command.BattleCommand;
import com.deltabattle.data.Encounter;
import com.deltabattle.data.EncounterManager;
import com.deltabattle.net.C2S;
import com.deltabattle.net.Net;
import com.deltabattle.net.S2C;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Серверная часть редактора битв: доступ только в креативе (или оператору), сохранение в папку мира. */
public final class EditorServer {
    private static final Map<UUID, StringBuilder> UPLOADS = new HashMap<>();

    public static boolean allowed(ServerPlayer p) { return p.isCreative() || p.hasPermissions(2); }

    private static void msg(ServerPlayer p, String s) { p.sendSystemMessage(Component.literal("[DeltaBattle] " + s)); }

    private static ResourceLocation rl(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation r = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id.trim()) : ResourceLocation.tryParse("custom:" + id.trim());
        return r;
    }

    /** Открыть у игрока список битв (или сразу редактор битвы, если id задан). */
    public static void open(ServerPlayer p, String id) {
        if (!allowed(p)) { msg(p, "Editor needs creative mode."); return; }
        if (id == null || id.isEmpty()) {
            String[] ids = EncounterManager.ids().stream().map(ResourceLocation::toString).toArray(String[]::new);
            Net.toPlayer(p, new S2C(S2C.EDITOR_LIST).strs(ids));
            return;
        }
        ResourceLocation r = rl(id);
        if (r == null) { msg(p, "Bad id: " + id); return; }
        Encounter e = EncounterManager.get(r);
        if (e == null) e = Encounter.template(r.toString());
        Net.toPlayer(p, new S2C(S2C.EDITOR_DATA).strs(r.toString(), Encounter.PRETTY.toJson(e)));
    }

    public static void onPacket(ServerPlayer p, C2S pkt) {
        if (!allowed(p)) { msg(p, "Editor needs creative mode."); return; }
        switch (pkt.kind) {
            case C2S.EDIT_LIST -> open(p, null);
            case C2S.EDIT_REQUEST -> open(p, pkt.id);
            case C2S.EDIT_DELETE -> {
                ResourceLocation r = rl(pkt.id);
                msg(p, r != null && EncounterManager.delete(r) ? "Deleted " + r : "Nothing to delete (datapack battles can't be deleted here).");
                open(p, null);
            }
            case C2S.EDIT_SPAWN -> {
                ResourceLocation r = rl(pkt.id);
                Encounter e = r == null ? null : EncounterManager.get(r);
                if (e == null || !(p.level() instanceof ServerLevel level)) { msg(p, "Save the battle first."); return; }
                BattleCommand.spawn(level, p.position(), e, r.toString());
                msg(p, "Spawned a mob for " + r);
            }
            case C2S.EDIT_UPLOAD -> upload(p, pkt);
            default -> { }
        }
    }

    private static void upload(ServerPlayer p, C2S pkt) {
        if (pkt.total <= 0 || pkt.total > 60 || pkt.part < 0 || pkt.part >= pkt.total) return;
        StringBuilder sb = pkt.part == 0 ? new StringBuilder() : UPLOADS.get(p.getUUID());
        if (sb == null) return;
        sb.append(pkt.data);
        UPLOADS.put(p.getUUID(), sb);
        if (pkt.part != pkt.total - 1) return;
        UPLOADS.remove(p.getUUID());
        Encounter enc;
        try {
            enc = Encounter.GSON.fromJson(sb.toString(), Encounter.class);
            if (enc == null) throw new IllegalStateException("empty");
        } catch (Exception ex) {
            msg(p, "Invalid data: " + ex.getMessage());
            return;
        }
        ResourceLocation r = rl(pkt.id);
        if (pkt.mode == 0) {
            if (r == null) { msg(p, "Bad id: " + pkt.id); return; }
            enc.normalize(r.toString());
            String err = EncounterManager.save(r, enc);
            msg(p, err == null ? "Saved " + r + "  (/deltabattle start " + r + ")" : "Save failed: " + err);
        } else {
            enc.normalize("editor_test");
            if (!BattleManager.startEncounter(p, enc, false)) msg(p, "Can't start a test battle now.");
        }
    }

    public static void onLogout(ServerPlayer p) { UPLOADS.remove(p.getUUID()); }
}
