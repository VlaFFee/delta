package com.deltabattle.command;

import com.deltabattle.battle.BattleManager;
import com.deltabattle.data.Encounter;
import com.deltabattle.data.EncounterManager;
import com.deltabattle.editor.EditorServer;
import com.deltabattle.entity.BattleMobEntity;
import com.deltabattle.registry.ModEntities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.List;

/**
 * /deltabattle start &lt;битва&gt; [игроки]   - начать бой без моба
 * /deltabattle summon &lt;битва&gt; [позиция] - создать моба, который начнёт эту битву при контакте
 * /deltabattle list                      - список загруженных битв
 */
public final class BattleCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("deltabattle").requires(s -> s.hasPermission(2) || (s.getEntity() instanceof ServerPlayer sp && sp.isCreative()))
            .then(Commands.literal("start").then(Commands.argument("encounter", ResourceLocationArgument.id())
                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(EncounterManager.ids(), b))
                .executes(c -> start(c, List.of(c.getSource().getPlayerOrException())))
                .then(Commands.argument("targets", EntityArgument.players())
                    .executes(c -> start(c, EntityArgument.getPlayers(c, "targets"))))))
            .then(Commands.literal("summon").then(Commands.argument("encounter", ResourceLocationArgument.id())
                .suggests((c, b) -> SharedSuggestionProvider.suggestResource(EncounterManager.ids(), b))
                .executes(c -> summon(c, c.getSource().getPosition()))
                .then(Commands.argument("pos", Vec3Argument.vec3()).executes(c -> summon(c, Vec3Argument.getVec3(c, "pos"))))))
            .then(Commands.literal("edit")
                .executes(c -> { EditorServer.open(c.getSource().getPlayerOrException(), null); return 1; })
                .then(Commands.argument("encounter", ResourceLocationArgument.id())
                    .suggests((c, b) -> SharedSuggestionProvider.suggestResource(EncounterManager.ids(), b))
                    .executes(c -> { EditorServer.open(c.getSource().getPlayerOrException(), ResourceLocationArgument.getId(c, "encounter").toString()); return 1; })))
            .then(Commands.literal("abort")
                .executes(c -> abort(List.of(c.getSource().getPlayerOrException())))
                .then(Commands.argument("targets", EntityArgument.players()).executes(c -> abort(EntityArgument.getPlayers(c, "targets")))))
            .then(Commands.literal("abortall").executes(c -> BattleManager.abortAll()))
            .then(Commands.literal("list").executes(c -> {
                for (var id : EncounterManager.ids()) c.getSource().sendSuccess(() -> Component.literal(id.toString()), false);
                return EncounterManager.ids().size();
            })));
    }

    private static int abort(Collection<ServerPlayer> players) {
        int n = 0;
        for (ServerPlayer p : players) if (BattleManager.abort(p)) n++;
        return n;
    }

    private static Encounter find(CommandContext<CommandSourceStack> c) {
        var rl = ResourceLocationArgument.getId(c, "encounter");
        Encounter e = EncounterManager.get(rl.toString());
        if (e == null) c.getSource().sendFailure(Component.literal("Unknown encounter: " + rl));
        return e;
    }

    private static int start(CommandContext<CommandSourceStack> c, Collection<ServerPlayer> players) throws CommandSyntaxException {
        Encounter enc = find(c);
        if (enc == null) return 0;
        int n = 0;
        for (ServerPlayer p : players) if (BattleManager.startEncounter(p, enc, false)) n++;
        return n;
    }

    private static int summon(CommandContext<CommandSourceStack> c, Vec3 pos) {
        Encounter enc = find(c);
        if (enc == null) return 0;
        return spawn(c.getSource().getLevel(), pos, enc, ResourceLocationArgument.getId(c, "encounter").toString()) ? 1 : 0;
    }

    /** Создаёт моба, который начнёт указанную битву при контакте. */
    public static boolean spawn(ServerLevel level, Vec3 pos, Encounter enc, String id) {
        BattleMobEntity m = ModEntities.BATTLE_MOB.get().create(level);
        if (m == null) return false;
        m.moveTo(pos.x, pos.y, pos.z, 0, 0);
        m.setEncounterId(id);
        m.setTex(enc.world_texture.isEmpty() ? enc.enemies.get(0).texture : enc.world_texture);
        m.setScale2(enc.world_scale);
        m.setHopping(!enc.world_behavior.equalsIgnoreCase("still"));
        Encounter.ModelDef md = enc.enemies.get(0).model;
        m.setModelJson(md.isSet() ? Encounter.GSON.toJson(md) : "");
        level.addFreshEntity(m);
        return true;
    }
}
