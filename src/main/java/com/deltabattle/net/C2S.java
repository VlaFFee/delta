package com.deltabattle.net;

import com.deltabattle.battle.BattleManager;
import com.deltabattle.editor.EditorServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Клиент -> сервер: выбор команды, позиция души и команды редактора. */
public class C2S {
    public static final int CHOICE = 0, SOUL = 1, LEAVE = 2,
            EDIT_LIST = 3, EDIT_REQUEST = 4, EDIT_UPLOAD = 5, EDIT_SPAWN = 6, EDIT_DELETE = 7, ABORT = 8;
    /** Размер куска JSON при загрузке из редактора (пакет клиент->сервер не более 32 КБ). */
    public static final int CHUNK = 8000;
    public int kind, cmd, sub, target, acc;
    public float x, y;
    public String id = "", data = "";
    public int mode, part, total;

    public static C2S choice(int cmd, int sub, int target, int acc) { C2S p = new C2S(); p.kind = CHOICE; p.cmd = cmd; p.sub = sub; p.target = target; p.acc = acc; return p; }
    public static C2S soul(float x, float y) { C2S p = new C2S(); p.kind = SOUL; p.x = x; p.y = y; return p; }
    /** Отмена боя (креатив / OP) - клавиша DELETE. */
    public static C2S abort() { C2S p = new C2S(); p.kind = ABORT; return p; }
    public static C2S leave() { C2S p = new C2S(); p.kind = LEAVE; return p; }
    public static C2S edit(int kind, String id) { C2S p = new C2S(); p.kind = kind; p.id = id; return p; }
    /** mode: 0 - сохранить, 1 - запустить тестовый бой без сохранения. */
    public static C2S upload(String id, int mode, int part, int total, String data) {
        C2S p = new C2S(); p.kind = EDIT_UPLOAD; p.id = id; p.mode = mode; p.part = part; p.total = total; p.data = data; return p;
    }

    public static void encode(C2S p, FriendlyByteBuf b) {
        b.writeByte(p.kind);
        if (p.kind == CHOICE) { b.writeByte(p.cmd); b.writeByte(p.sub); b.writeByte(p.target); b.writeShort(p.acc); }
        else if (p.kind == SOUL) { b.writeShort(Math.round(p.x * 16)); b.writeShort(Math.round(p.y * 16)); } // компактно: 1/16 px
        else if (p.kind >= EDIT_REQUEST && p.kind <= EDIT_DELETE) {
            b.writeUtf(p.id, 200);
            if (p.kind == EDIT_UPLOAD) { b.writeByte(p.mode); b.writeShort(p.part); b.writeShort(p.total); b.writeUtf(p.data, CHUNK); }
        }
    }

    public static C2S decode(FriendlyByteBuf b) {
        C2S p = new C2S(); p.kind = b.readByte();
        if (p.kind == CHOICE) { p.cmd = b.readByte(); p.sub = b.readByte(); p.target = b.readByte(); p.acc = b.readShort(); }
        else if (p.kind == SOUL) { p.x = b.readShort() / 16f; p.y = b.readShort() / 16f; }
        else if (p.kind >= EDIT_REQUEST && p.kind <= EDIT_DELETE) {
            p.id = b.readUtf(200);
            if (p.kind == EDIT_UPLOAD) { p.mode = b.readByte(); p.part = b.readShort(); p.total = b.readShort(); p.data = b.readUtf(CHUNK); }
        }
        return p;
    }

    public static void handle(C2S p, Supplier<NetworkEvent.Context> ctx) {
        var c = ctx.get();
        c.enqueueWork(() -> {
            if (c.getSender() == null) return;
            if (p.kind >= EDIT_LIST && p.kind <= EDIT_DELETE) EditorServer.onPacket(c.getSender(), p);
            else BattleManager.onPacket(c.getSender(), p);
        });
        c.setPacketHandled(true);
    }
}
