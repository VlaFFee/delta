package com.deltabattle.net;

import com.deltabattle.DeltaBattle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Net {
    private static final String VER = "3";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(DeltaBattle.MODID, "main"), () -> VER, VER::equals, VER::equals);

    public static void init() {
        int id = 0;
        CH.registerMessage(id++, S2C.class, S2C::encode, S2C::decode, S2C::handle);
        CH.registerMessage(id++, C2S.class, C2S::encode, C2S::decode, C2S::handle);
    }
    public static void toPlayer(ServerPlayer p, S2C pkt) { CH.send(PacketDistributor.PLAYER.with(() -> p), pkt); }
    public static void toServer(C2S pkt) { CH.sendToServer(pkt); }
}
