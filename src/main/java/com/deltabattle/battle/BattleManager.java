package com.deltabattle.battle;

import com.deltabattle.data.Encounter;
import com.deltabattle.data.EncounterManager;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.net.C2S;
import com.deltabattle.party.PartyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.*;

public final class BattleManager {
    private static final Map<UUID, Battle> BY_PLAYER = new HashMap<>();
    private static final List<Battle> ACTIVE = new ArrayList<>();
    private static int counter;

    public static boolean inBattle(ServerPlayer p) { return BY_PLAYER.containsKey(p.getUUID()); }

    /** Бой с мобом из мира: в него входит вся пати инициатора, находящаяся рядом (до 4 игроков). */
    public static void start(ServerPlayer starter, BattleMobEntity mob) {
        if (inBattle(starter) || mob.isInBattle() || !(mob.level() instanceof ServerLevel level)) return;
        Encounter enc = EncounterManager.get(mob.getEncounterId());
        if (enc == null) { starter.sendSystemMessage(Component.literal("Unknown encounter: " + mob.getEncounterId())); return; }
        List<ServerPlayer> list = new ArrayList<>();
        list.add(starter);
        for (ServerPlayer p : PartyManager.membersOf(starter)) {
            if (p != starter && list.size() < PartyManager.MAX && !inBattle(p) && p.level() == level
                    && p.distanceToSqr(mob) < 32 * 32) list.add(p);
        }
        launch(level, mob, enc, list);
    }

    /** Бой без моба (команда /deltabattle start). */
    public static boolean startEncounter(ServerPlayer starter, Encounter enc, boolean wholeParty) {
        if (inBattle(starter) || !(starter.level() instanceof ServerLevel level)) return false;
        List<ServerPlayer> list = new ArrayList<>();
        list.add(starter);
        if (wholeParty) {
            for (ServerPlayer p : PartyManager.membersOf(starter)) {
                if (p != starter && list.size() < PartyManager.MAX && !inBattle(p) && p.level() == level) list.add(p);
            }
        }
        launch(level, null, enc, list);
        return true;
    }

    private static void launch(ServerLevel level, BattleMobEntity mob, Encounter enc, List<ServerPlayer> list) {
        Battle b = new Battle(++counter, level, mob, enc, list);
        ACTIVE.add(b);
        for (ServerPlayer p : list) BY_PLAYER.put(p.getUUID(), b);
    }

    public static void onPacket(ServerPlayer p, C2S pkt) {
        Battle b = BY_PLAYER.get(p.getUUID());
        if (b == null) return;
        switch (pkt.kind) {
            case C2S.CHOICE -> b.onChoice(p, pkt.cmd, pkt.sub, pkt.target, pkt.acc);
            case C2S.SOUL -> b.onSoul(p, pkt.x, pkt.y);
            case C2S.ABORT -> { if (com.deltabattle.editor.EditorServer.allowed(p)) b.end(Battle.RES_ABORT); }
            default -> {}
        }
    }

    public static void tick() {
        for (int i = ACTIVE.size() - 1; i >= 0; i--) { if (i < ACTIVE.size()) ACTIVE.get(i).tick(); }
    }

    static void remove(Battle b) {
        ACTIVE.remove(b);
        for (Battle.Member m : b.members) BY_PLAYER.remove(m.p.getUUID(), b);
    }

    /** Отменяет бой игрока (без наград). */
    public static boolean abort(ServerPlayer p) {
        Battle b = BY_PLAYER.get(p.getUUID());
        if (b == null) return false;
        b.end(Battle.RES_ABORT);
        return true;
    }

    public static int abortAll() {
        int n = 0;
        for (Battle b : new ArrayList<>(ACTIVE)) { b.end(Battle.RES_ABORT); n++; }
        return n;
    }

    public static void onLogout(ServerPlayer p) {
        Battle b = BY_PLAYER.get(p.getUUID());
        if (b != null) b.end(Battle.RES_ABORT);
    }
}
