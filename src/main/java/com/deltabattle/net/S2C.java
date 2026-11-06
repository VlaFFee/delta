package com.deltabattle.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Универсальный пакет сервер -> клиент. Тип определяет смысл полей. */
public class S2C {
    public static final int START = 0, PHASE = 1, EVENTS = 2, STATE = 3, AIM = 4, HIT = 5, SOULS = 6, END = 7, READY = 8,
            MUSIC = 9, EDITOR_LIST = 10, EDITOR_DATA = 11;
    public int kind;
    public long l;
    public int[] i = new int[0];
    public float[] f = new float[0];
    public String[] s = new String[0];

    public S2C(int kind) { this.kind = kind; }
    public S2C ints(int... v) { i = v; return this; }
    public S2C floats(float... v) { f = v; return this; }
    public S2C strs(String... v) { s = v; return this; }
    public S2C lng(long v) { l = v; return this; }

    public static void encode(S2C p, FriendlyByteBuf b) {
        b.writeByte(p.kind); b.writeLong(p.l);
        b.writeVarInt(p.i.length); for (int v : p.i) b.writeVarInt(v);
        b.writeVarInt(p.f.length); for (float v : p.f) b.writeFloat(v);
        b.writeVarInt(p.s.length); for (String v : p.s) b.writeUtf(v, 200000);
    }

    public static S2C decode(FriendlyByteBuf b) {
        S2C p = new S2C(b.readByte()); p.l = b.readLong();
        p.i = new int[b.readVarInt()]; for (int k = 0; k < p.i.length; k++) p.i[k] = b.readVarInt();
        p.f = new float[b.readVarInt()]; for (int k = 0; k < p.f.length; k++) p.f[k] = b.readFloat();
        p.s = new String[b.readVarInt()]; for (int k = 0; k < p.s.length; k++) p.s[k] = b.readUtf(200000);
        return p;
    }

    public static void handle(S2C p, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> { if (p.kind >= MUSIC) com.deltabattle.client.ClientExtras.handle(p); else com.deltabattle.client.ClientBattle.handle(p); }));
        ctx.get().setPacketHandled(true);
    }
}
