package com.deltabattle.party;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;

public final class PartyCommand {
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("dparty")
            .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player())
                .executes(c -> { PartyManager.invite(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")); return 1; })))
            .then(Commands.literal("accept").executes(c -> { PartyManager.accept(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("decline").executes(c -> { PartyManager.decline(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("leave").executes(c -> { PartyManager.leave(c.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("list").executes(c -> { PartyManager.list(c.getSource().getPlayerOrException()); return 1; })));
    }
}
