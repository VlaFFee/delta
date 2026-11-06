package com.deltabattle.party;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Пати (до 4 игроков) и приглашения с таймаутом 60 секунд. */
public final class PartyManager {
    public static final int MAX = 4;
    private static final long INVITE_TTL = 20 * 60;
    private record Invite(UUID from, long expires) {}

    private static final Map<UUID, List<UUID>> PARTY_OF = new HashMap<>(); // игрок -> общий список (первый = лидер)
    private static final Map<UUID, Invite> INVITES = new HashMap<>();

    public static List<ServerPlayer> membersOf(ServerPlayer p) {
        List<UUID> ids = PARTY_OF.get(p.getUUID());
        List<ServerPlayer> out = new ArrayList<>();
        if (ids == null) { out.add(p); return out; }
        for (UUID id : ids) { ServerPlayer o = p.server.getPlayerList().getPlayer(id); if (o != null) out.add(o); }
        return out;
    }

    public static void invite(ServerPlayer from, ServerPlayer to) {
        if (from == to) { from.sendSystemMessage(Component.translatable("deltabattle.party.self")); return; }
        List<UUID> party = PARTY_OF.get(from.getUUID());
        if (party != null && party.get(0) != from.getUUID()) { from.sendSystemMessage(Component.translatable("deltabattle.party.not_leader")); return; }
        if (party != null && party.size() >= MAX) { from.sendSystemMessage(Component.translatable("deltabattle.party.full")); return; }
        if (PARTY_OF.containsKey(to.getUUID())) { from.sendSystemMessage(Component.translatable("deltabattle.party.already")); return; }
        INVITES.put(to.getUUID(), new Invite(from.getUUID(), from.server.getTickCount() + INVITE_TTL));
        from.sendSystemMessage(Component.translatable("deltabattle.party.sent", to.getName()));
        Component accept = Component.translatable("deltabattle.party.accept_btn").setStyle(Style.EMPTY.withBold(true)
                .withColor(0x55FF55).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/dparty accept")));
        to.sendSystemMessage(Component.translatable("deltabattle.party.invited", from.getName()).append(" ").append(accept));
    }

    public static void accept(ServerPlayer p) {
        Invite inv = INVITES.remove(p.getUUID());
        MinecraftServer s = p.server;
        ServerPlayer from = inv == null ? null : s.getPlayerList().getPlayer(inv.from);
        if (inv == null || inv.expires < s.getTickCount() || from == null) { p.sendSystemMessage(Component.translatable("deltabattle.party.no_invite")); return; }
        List<UUID> party = PARTY_OF.get(from.getUUID());
        if (party == null) { party = new ArrayList<>(); party.add(from.getUUID()); PARTY_OF.put(from.getUUID(), party); }
        if (party.size() >= MAX) { p.sendSystemMessage(Component.translatable("deltabattle.party.full")); return; }
        party.add(p.getUUID()); PARTY_OF.put(p.getUUID(), party);
        for (ServerPlayer m : membersOf(p)) m.sendSystemMessage(Component.translatable("deltabattle.party.joined", p.getName()));
    }

    public static void decline(ServerPlayer p) {
        Invite inv = INVITES.remove(p.getUUID());
        if (inv != null) { ServerPlayer f = p.server.getPlayerList().getPlayer(inv.from); if (f != null) f.sendSystemMessage(Component.translatable("deltabattle.party.declined", p.getName())); }
    }

    public static void leave(ServerPlayer p) {
        List<UUID> party = PARTY_OF.remove(p.getUUID());
        if (party == null) { p.sendSystemMessage(Component.translatable("deltabattle.party.none")); return; }
        party.remove(p.getUUID());
        p.sendSystemMessage(Component.translatable("deltabattle.party.left"));
        if (party.size() <= 1) { for (UUID id : party) PARTY_OF.remove(id); }
        else for (ServerPlayer m : membersOf(p.server.getPlayerList().getPlayer(party.get(0)))) m.sendSystemMessage(Component.translatable("deltabattle.party.left_other", p.getName()));
    }

    public static void list(ServerPlayer p) {
        List<ServerPlayer> ms = membersOf(p);
        p.sendSystemMessage(Component.translatable("deltabattle.party.list"));
        for (ServerPlayer m : ms) p.sendSystemMessage(Component.literal(" - ").append(m.getName()));
    }

    public static void onLogout(ServerPlayer p) { INVITES.remove(p.getUUID()); if (PARTY_OF.containsKey(p.getUUID())) leave(p); }
}
